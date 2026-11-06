package com.lumentv.phone

import com.lumentv.AppCore
import com.lumentv.core.ChannelQuality
import com.lumentv.core.SecretBox
import com.lumentv.core.explainFailure
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.xtream.Presence
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Every playback goes through here, as on the TV (PlayerViewModel): the languages of the settings, the other screens
 * of the subscription (presence through the sync server: a warning first, "Lancer quand même"), the heartbeat while
 * it plays, and what the player adds by kind (skips of an episode, lower qualities of a channel).
 */
object Playback {
    /** A warning to answer before playing: another device of the same subscription is playing. */
    class Pending(val text: String, val go: () -> Unit)

    val pending = MutableStateFlow<Pending?>(null)

    /** [playlistId]: the stream's list (null for a downloaded file, which takes no screen). */
    suspend fun start(core: AppCore, player: VideoPlayer, request: PlayRequest, playlistId: Long?) {
        val s = core.settings.current()
        request.audioLang = s.audioLang.ifBlank { "fr" }
        request.subtitleLang = s.subtitleLang.ifBlank { "fr" }
        request.subtitlesOn = s.subtitlesOn
        request.stableBuffer = s.bufferMode == "stable"
        request.resizeMode = s.resizeMode
        val p = playlistId?.let { core.playlists.get(it) }
        val key = p?.takeIf { core.presence.enabled && !request.url.startsWith(DownloadManager.SCHEME) }?.let {
            Presence.key(it.type, SecretBox.decrypt(it.sourceEnc), it.username, SecretBox.decrypt(it.passwordEnc))
        }
        val go = {
            var beat: Job? = null
            if (key != null) beat = core.scope.launch { while (true) { core.presence.beat(key); delay(Presence.BEAT_MS) } }
            request.onClosed = { beat?.cancel(); if (key != null) core.scope.launch { core.presence.leave(key) } }
            request.explain = { done -> core.scope.launch { done(runCatching { core.explainFailure() }.getOrDefault("")) } }
            player.play(request)
        }
        val others = key?.let { core.presence.others(it) }.orEmpty()
        if (others.isNotEmpty()) pending.value = Pending(Presence.warning(others), go) else go()
    }

    /** An episode's recap, intro and credits, when TheIntroDB (or its fallbacks) knows the series by its TMDB id. */
    fun loadSkips(core: AppCore, request: PlayRequest, tmdb: String?, season: Int, episode: Int) {
        if (tmdb.isNullOrBlank()) return
        core.scope.launch {
            val seg = runCatching { core.introDb.segments(tmdb, season, episode) }.getOrNull() ?: return@launch
            request.skips = listOfNotNull(
                seg.recap?.let { PlaySkip(it.first, it.last, "Passer le résumé", false) },
                seg.intro?.let { PlaySkip(it.first, it.last, "Passer l'intro", false) },
                seg.credits?.let { PlaySkip(it.first, it.last, "Épisode suivant", true) },
            )
        }
    }

    /** The same channel in lower qualities than [c] (FHD → HD → SD), best first, when "Qualité auto" is on. */
    suspend fun lowerVariants(core: AppCore, c: ChannelRow): List<PlayVariant> {
        if (!core.settings.current().autoQuality) return emptyList()
        val base = ChannelQuality.base(c.ch.name)
        if (base.length < 2) return emptyList()
        val rank = ChannelQuality.rank(c.ch.name)
        return core.library.channelsNamed(c.ch.playlistId, base)
            .filter { ChannelQuality.base(it.ch.name) == base && ChannelQuality.rank(it.ch.name) < rank && !it.isHidden }
            .distinctBy { ChannelQuality.rank(it.ch.name) }
            .sortedByDescending { ChannelQuality.rank(it.ch.name) }
            .mapNotNull { v -> runCatching { PlayVariant(core.resolver.channel(v.ch).url, ChannelQuality.label(v.ch.name)) }.getOrNull() }
    }
}
