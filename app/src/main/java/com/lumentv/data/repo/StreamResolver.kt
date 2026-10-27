package com.lumentv.data.repo

import com.lumentv.data.db.ChannelEntity
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.MovieEntity
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.db.ProgramEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class StreamSpec(val url: String, val userAgent: String?, val referer: String?, val live: Boolean)

/** Turns a channel / movie / episode / past programme into the URL and headers the player needs. */
class StreamResolver(private val playlists: PlaylistRepository) {
    private val cache = HashMap<Long, PlaylistEntity>()

    private suspend fun playlist(id: Long): PlaylistEntity =
        cache[id] ?: (playlists.get(id) ?: error("playlist $id")).also { cache[id] = it }

    fun forget() = cache.clear()

    suspend fun channel(c: ChannelEntity): StreamSpec {
        val p = playlist(c.playlistId)
        val url = if (p.type == PlaylistType.XTREAM) playlists.xtream(p).liveUrl(c.streamId, p.liveFormat) else c.url
        return StreamSpec(url, c.userAgent ?: playlists.userAgentFor(p), c.referer, live = true)
    }

    suspend fun movie(m: MovieEntity): StreamSpec {
        val p = playlist(m.playlistId)
        val url = if (p.type == PlaylistType.XTREAM) playlists.xtream(p).movieUrl(m.streamId, m.ext) else m.url
        return StreamSpec(url, playlists.userAgentFor(p), null, live = false)
    }

    suspend fun episode(e: EpisodeEntity): StreamSpec {
        val p = playlist(e.playlistId)
        val url = if (p.type == PlaylistType.XTREAM && e.streamId > 0) playlists.xtream(p).episodeUrl(e.streamId, e.ext) else e.url
        return StreamSpec(url, playlists.userAgentFor(p), null, live = false)
    }

    suspend fun canCatchup(c: ChannelEntity): Boolean {
        if (c.catchupDays <= 0) return false
        val p = playlist(c.playlistId)
        return p.type == PlaylistType.XTREAM || c.catchupSource != null
    }

    /** A past programme on a channel with catch-up (Xtream timeshift, or the M3U catchup-source template). */
    suspend fun catchup(c: ChannelEntity, program: ProgramEntity): StreamSpec? {
        if (!canCatchup(c)) return null
        val p = playlist(c.playlistId)
        val minutes = ((program.stop - program.start) / 60_000L).toInt().coerceAtLeast(1)
        val url = if (p.type == PlaylistType.XTREAM) {
            playlists.xtream(p).catchupUrl(c.streamId, program.start, minutes, p.serverTimeZone)
        } else {
            val template = c.catchupSource ?: return null
            val filled = fillTemplate(template, program.start, program.stop)
            if (filled.contains("://")) filled else c.url + filled
        }
        return StreamSpec(url, c.userAgent ?: playlists.userAgentFor(p), c.referer, live = false)
    }

    companion object {
        /** Supports the common catch-up placeholders: {utc}, {start}, {lutc}, {end}, {utcend}, {duration}, {Y}{m}{d}{H}{M}{S}. */
        fun fillTemplate(template: String, startMs: Long, stopMs: Long, nowMs: Long = System.currentTimeMillis()): String {
            val utc = (startMs / 1000).toString()
            val end = (stopMs / 1000).toString()
            val dur = ((stopMs - startMs) / 1000).toString()
            fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(startMs))
            return template
                .replace("\${start}", utc).replace("{start}", utc).replace("{utc}", utc)
                .replace("\${end}", end).replace("{end}", end).replace("{utcend}", end)
                .replace("{lutc}", (nowMs / 1000).toString()).replace("\${timestamp}", (nowMs / 1000).toString())
                .replace("{duration}", dur).replace("\${duration}", dur)
                .replace("{Y}", fmt("yyyy")).replace("{m}", fmt("MM")).replace("{d}", fmt("dd"))
                .replace("{H}", fmt("HH")).replace("{M}", fmt("mm")).replace("{S}", fmt("ss"))
        }
    }
}

