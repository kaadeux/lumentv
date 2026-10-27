package com.lumentv.ui.player

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumentv.AppGraph
import com.lumentv.core.userMessage
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.db.PrefKind
import com.lumentv.data.db.ProgramEntity
import com.lumentv.data.repo.Groups
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.NowNext
import com.lumentv.data.settings.Keys
import com.lumentv.data.settings.Settings
import com.lumentv.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class Mode { LIVE, CATCHUP, MOVIE, EPISODE, FILE }

/** Everything the player screen shows, kept across onStop/onStart (the ExoPlayer itself is released on stop). */
class PlayerViewModel(val graph: AppGraph) : ViewModel() {
    var mode by mutableStateOf(Mode.LIVE)
    var settings by mutableStateOf(Settings())
    var resizeMode by mutableIntStateOf(0)
    var loaded by mutableStateOf(false)
    var fatal by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)

    // Live
    var channels by mutableStateOf<List<ChannelRow>>(emptyList())
    var index by mutableIntStateOf(-1)
    var group: String? = null
    var nowNext by mutableStateOf(NowNext(null, null))
    private var previousChannelId: Long = 0
    val channel: ChannelRow? get() = channels.getOrNull(index)

    // Catch-up / on demand
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var image: String? = null
    var catchupProgram: ProgramEntity? = null
    private var historyKey: String? = null
    private var playlistId: Long = 0
    private var uid: String = ""
    var seriesUid: String? = null
        private set
    var episodes: List<EpisodeEntity> = emptyList()
    var nextEpisode by mutableStateOf<EpisodeEntity?>(null)
    private var resumeAt = 0L
    private var filePath: String? = null

    var sleepAt by mutableLongStateOf(0L)

    var controller: PlayerController? = null
        private set
    private var zapJob: Job? = null

    fun load(intent: Intent) = viewModelScope.launch {
        settings = graph.settings.current()
        resizeMode = settings.resizeMode
        try {
            when (intent.getStringExtra(PlayerLauncher.KIND)) {
                "live", "catchup" -> {
                    val id = intent.getLongExtra(PlayerLauncher.CHANNEL_ID, 0)
                    val row = graph.library.channel(id) ?: return@launch fail("Chaîne introuvable.")
                    group = intent.getStringExtra(PlayerLauncher.GROUP)
                    val list = graph.library.channels(row.ch.playlistId, group ?: Groups.ALL, settings.showHidden, settings.sort)
                        .let { if (it.any { c -> c.ch.id == id }) it else graph.library.channels(row.ch.playlistId, Groups.ALL, true, "provider") }
                    channels = list
                    index = list.indexOfFirst { it.ch.id == id }.coerceAtLeast(0)
                    if (intent.getStringExtra(PlayerLauncher.KIND) == "catchup") {
                        mode = Mode.CATCHUP
                        val start = intent.getLongExtra(PlayerLauncher.START, 0)
                        catchupProgram = graph.epg.programs(row, start - 1, start + 60_000).firstOrNull { it.start == start }
                            ?: ProgramEntity(playlistId = row.ch.playlistId, epgKey = "", start = start, stop = intent.getLongExtra(PlayerLauncher.STOP, start),
                                title = intent.getStringExtra(PlayerLauncher.TITLE).orEmpty(), titleNorm = "")
                        title = catchupProgram!!.title
                        subtitle = row.ch.name + " · rattrapage"
                    } else mode = Mode.LIVE
                }
                "movie" -> {
                    mode = Mode.MOVIE
                    playlistId = intent.getLongExtra(PlayerLauncher.PLAYLIST_ID, 0)
                    uid = intent.getStringExtra(PlayerLauncher.UID).orEmpty()
                    val m = graph.library.movieByUid(playlistId, uid) ?: return@launch fail("Film introuvable dans la playlist.")
                    title = m.m.name; subtitle = m.m.year; image = m.m.poster
                    historyKey = LibraryRepository.key(HistoryKind.MOVIE, playlistId, uid)
                    if (intent.getBooleanExtra(PlayerLauncher.RESUME, false)) resumeAt = graph.library.history(historyKey!!)?.positionMs ?: 0
                }
                "episode" -> {
                    mode = Mode.EPISODE
                    playlistId = intent.getLongExtra(PlayerLauncher.PLAYLIST_ID, 0)
                    seriesUid = intent.getStringExtra(PlayerLauncher.SERIES_UID)
                    uid = intent.getStringExtra(PlayerLauncher.UID).orEmpty()
                    episodes = loadEpisodes(playlistId, seriesUid.orEmpty())
                    val e = episodes.firstOrNull { it.uid == uid } ?: return@launch fail("Épisode introuvable.")
                    setEpisode(e)
                    if (intent.getBooleanExtra(PlayerLauncher.RESUME, false)) resumeAt = graph.library.history(historyKey!!)?.positionMs ?: 0
                }
                "file" -> {
                    mode = Mode.FILE
                    val t = graph.transfers.get(intent.getLongExtra(PlayerLauncher.TRANSFER_ID, 0)) ?: return@launch fail("Fichier introuvable.")
                    val f = java.io.File(t.filePath ?: "")
                    if (!f.exists()) return@launch fail("Le fichier n'existe plus : ${f.name}")
                    title = t.title; subtitle = t.subtitle; filePath = f.absolutePath
                    playlistId = t.playlistId; uid = "file:${t.id}"
                    historyKey = LibraryRepository.key(HistoryKind.FILE, t.playlistId, "file:${t.id}")
                    resumeAt = graph.library.history(historyKey!!)?.positionMs ?: 0
                }
                else -> return@launch fail("Contenu inconnu.")
            }
            checkScreens()
            loaded = true
            if (screenWarning == null) controller?.let { start(it) }
        } catch (t: Throwable) {
            fail(userMessage(t))
        }
    }

    private suspend fun loadEpisodes(pid: Long, sUid: String): List<EpisodeEntity> {
        val stored = graph.library.storedEpisodes(pid, sUid)
        if (stored.isNotEmpty()) return stored
        val p = graph.playlists.get(pid) ?: return emptyList()
        val s = graph.library.seriesByUid(pid, sUid) ?: return emptyList()
        return if (p.type == PlaylistType.XTREAM) graph.playlists.loadXtreamEpisodes(p, sUid, s.s.seriesId) else emptyList()
    }

    private suspend fun setEpisode(e: EpisodeEntity) {
        uid = e.uid
        val series = graph.library.seriesByUid(playlistId, e.seriesUid)
        title = series?.s?.name ?: e.title
        subtitle = "S${e.season} E${e.episode} · ${e.title}"
        image = e.image ?: series?.s?.cover
        historyKey = LibraryRepository.key(HistoryKind.EPISODE, playlistId, e.uid)
        val i = episodes.indexOf(e)
        nextEpisode = episodes.getOrNull(i + 1)
    }

    private fun fail(message: String) { fatal = message; loaded = true }

    /** Set when every screen of the subscription is already in use; playback waits for the viewer's choice. */
    var screenWarning by mutableStateOf<String?>(null)
        private set
    private var counted = false
    private var presenceKey: String? = null
    private var beatJob: Job? = null

    /**
     * Before taking a screen: is another Lumen TV device playing on this subscription (presence through the sync
     * server), or does the provider (Xtream) say every screen is in use? The provider is not asked when this device
     * is the one it counts.
     */
    private suspend fun checkScreens() {
        if (mode == Mode.FILE) return
        val pid = channel?.ch?.playlistId ?: playlistId
        val p = graph.playlists.get(pid) ?: return
        presenceKey = com.lumentv.data.xtream.Presence.key(p.type, com.lumentv.core.SecretBox.decrypt(p.sourceEnc), p.username,
            com.lumentv.core.SecretBox.decrypt(p.passwordEnc))
        val others = graph.presence.others(presenceKey!!)
        if (others.isNotEmpty()) { screenWarning = com.lumentv.data.xtream.Presence.warning(others); return }
        if (p.type != com.lumentv.data.db.PlaylistType.XTREAM || com.lumentv.data.xtream.ScreenGuard.ownStreamCounted()) return
        val account = runCatching { kotlinx.coroutines.withTimeout(4_000) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.playlists.xtream(p).authenticate() } } }.getOrNull() ?: return
        screenWarning = com.lumentv.data.xtream.ScreenGuard.warning(account)
    }

    /** "Lancer quand même". */
    fun confirmScreens() { screenWarning = null; controller?.let { start(it) } }

    /** Called by the activity in onStart with a fresh player. */
    fun attach(c: PlayerController) {
        controller = c
        if (loaded && fatal == null && screenWarning == null) start(c)
    }

    /** Called in onStop, before the player is released. */
    fun detach() {
        controller?.let { saveProgress(it) }
        controller?.let { if (mode != Mode.LIVE) resumeAt = it.player.currentPosition }
        controller = null
        if (counted) {
            counted = false
            com.lumentv.data.xtream.ScreenGuard.stopped()
            beatJob?.cancel()
            presenceKey?.let { k -> graph.appScope.launch { graph.presence.leave(k) } }
        }
    }

    private fun start(c: PlayerController) = viewModelScope.launch {
        if (!counted) {
            counted = true
            com.lumentv.data.xtream.ScreenGuard.started()
            presenceKey?.let { k -> beatJob = viewModelScope.launch { while (true) { graph.presence.beat(k); kotlinx.coroutines.delay(com.lumentv.data.xtream.Presence.BEAT_MS) } } }
        }
        try {
            when (mode) {
                Mode.LIVE -> {
                    val row = channel ?: return@launch
                    c.play(graph.resolver.channel(row.ch))
                    nowNext = graph.epg.nowNext(row)
                    graph.settings.set(Keys.lastChannel, row.ch.id)
                    graph.library.record(HistoryEntity(
                        key = LibraryRepository.key(HistoryKind.CHANNEL, row.ch.playlistId, row.ch.uid), kind = HistoryKind.CHANNEL,
                        playlistId = row.ch.playlistId, uid = row.ch.uid, title = row.ch.name, subtitle = row.ch.groupName,
                        image = row.ch.logo, watchedAt = System.currentTimeMillis(),
                    ))
                }
                Mode.CATCHUP -> {
                    val row = channel ?: return@launch
                    val spec = graph.resolver.catchup(row.ch, catchupProgram ?: return@launch) ?: return@launch fail("Le rattrapage n'est pas disponible sur cette chaîne.")
                    c.play(spec, resumeAt)
                }
                Mode.MOVIE -> {
                    val m = graph.library.movieByUid(playlistId, uid) ?: return@launch fail("Film introuvable.")
                    c.play(graph.resolver.movie(m.m), resumeAt)
                }
                Mode.EPISODE -> {
                    val e = episodes.firstOrNull { it.uid == uid } ?: return@launch fail("Épisode introuvable.")
                    c.play(graph.resolver.episode(e), resumeAt)
                }
                Mode.FILE -> c.play(com.lumentv.data.repo.StreamSpec("file://" + filePath, null, null, live = false), resumeAt)
            }
        } catch (t: Throwable) {
            fail(userMessage(t))
        }
    }

    // ---- Live actions ---------------------------------------------------------------------------------------

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        goTo((index + delta).mod(channels.size))
    }

    fun goTo(newIndex: Int) {
        if (newIndex == index && mode == Mode.LIVE) return
        channel?.let { previousChannelId = it.ch.id }
        index = newIndex
        mode = Mode.LIVE
        catchupProgram = null
        resumeAt = 0
        zapJob?.cancel()
        // Quick successive presses only start the last channel.
        zapJob = viewModelScope.launch {
            nowNext = channel?.let { graph.epg.nowNext(it) } ?: NowNext(null, null)
            kotlinx.coroutines.delay(350)
            controller?.let { start(it) }
        }
    }

    fun previousChannel() {
        val i = channels.indexOfFirst { it.ch.id == previousChannelId }
        if (i >= 0) goTo(i) else toast = "Pas de chaîne précédente"
    }

    fun goToNumber(number: Int) {
        if (number == 0) return previousChannel()
        val i = channels.indexOfFirst { it.ch.number == number }
        if (i >= 0) goTo(i) else toast = "Chaîne $number introuvable dans cette liste"
    }

    fun playCatchup(row: ChannelRow, program: ProgramEntity) {
        channel?.let { previousChannelId = it.ch.id }
        index = channels.indexOf(row).takeIf { it >= 0 } ?: index
        mode = Mode.CATCHUP
        catchupProgram = program
        title = program.title
        subtitle = row.ch.name + " · rattrapage"
        resumeAt = 0
        controller?.let { start(it) }
    }

    fun backToLive() {
        mode = Mode.LIVE
        catchupProgram = null
        resumeAt = 0
        controller?.let { start(it) }
    }

    fun toggleFavorite() = viewModelScope.launch {
        val row = channel ?: return@launch
        val fav = graph.library.toggleFavorite(row.ch.playlistId, PrefKind.CHANNEL, row.ch.uid)
        channels = channels.map { if (it.ch.id == row.ch.id) it.copy(favorite = fav) else it }
        toast = if (fav) "Ajoutée aux favoris" else "Retirée des favoris"
    }

    fun refreshNowNext() = viewModelScope.launch { channel?.let { nowNext = graph.epg.nowNext(it) } }

    // ---- On demand -----------------------------------------------------------------------------------------

    fun saveProgress(c: PlayerController) {
        val key = historyKey ?: return
        if (mode != Mode.MOVIE && mode != Mode.EPISODE && mode != Mode.FILE) return
        val pos = c.player.currentPosition
        val dur = c.player.duration.takeIf { it > 0 } ?: 0
        if (pos < 5_000) return
        val done = dur > 0 && pos > dur * 0.95
        viewModelScope.launch {
            graph.library.record(HistoryEntity(
                key = key, kind = when (mode) { Mode.MOVIE -> HistoryKind.MOVIE; Mode.FILE -> HistoryKind.FILE; else -> HistoryKind.EPISODE }, playlistId = playlistId,
                uid = uid, title = title, subtitle = subtitle, image = image, parentUid = seriesUid,
                watchedAt = System.currentTimeMillis(), positionMs = if (done) dur else pos, durationMs = dur,
            ))
        }
    }

    /** End of an episode: the next one starts when "auto next" is on. @return true if something new started. */
    fun onEnded(): Boolean {
        val c = controller ?: return false
        saveProgress(c)
        val next = nextEpisode ?: return false
        if (mode != Mode.EPISODE || !settings.autoNext) return false
        playEpisode(next)
        return true
    }

    fun playEpisode(e: EpisodeEntity) = viewModelScope.launch {
        controller?.let { saveProgress(it) }
        setEpisode(e)
        resumeAt = 0
        controller?.let { start(it) }
    }

    fun cycleResize() {
        resizeMode = (resizeMode + 1) % 3
        viewModelScope.launch { graph.settings.set(Keys.resize, resizeMode) }
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(graph) as T
    }
}
