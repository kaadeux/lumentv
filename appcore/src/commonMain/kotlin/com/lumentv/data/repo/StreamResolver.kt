package com.lumentv.data.repo

import com.lumentv.data.db.ChannelEntity
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.MovieEntity
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.db.ProgramEntity
import com.lumentv.core.nowMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class StreamSpec(val url: String, val userAgent: String?, val referer: String?, val live: Boolean)

/** Turns a channel / movie / episode / past programme into the URL and headers the player needs. */
class StreamResolver(private val playlists: PlaylistRepository) {
    private val cache = com.lumentv.core.Lru<Long, PlaylistEntity>(16)

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
        fun fillTemplate(template: String, startMs: Long, stopMs: Long, nowMs: Long = nowMillis()): String {
            val utc = (startMs / 1000).toString()
            val end = (stopMs / 1000).toString()
            val dur = ((stopMs - startMs) / 1000).toString()
            val t = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(TimeZone.UTC)
            fun two(n: Int) = n.toString().padStart(2, '0')
            return template
                .replace("\${start}", utc).replace("{start}", utc).replace("{utc}", utc)
                .replace("\${end}", end).replace("{end}", end).replace("{utcend}", end)
                .replace("{lutc}", (nowMs / 1000).toString()).replace("\${timestamp}", (nowMs / 1000).toString())
                .replace("{duration}", dur).replace("\${duration}", dur)
                .replace("{Y}", t.year.toString().padStart(4, '0')).replace("{m}", two(t.monthNumber)).replace("{d}", two(t.dayOfMonth))
                .replace("{H}", two(t.hour)).replace("{M}", two(t.minute)).replace("{S}", two(t.second))
        }
    }
}

