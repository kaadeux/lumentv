package com.lumentv.data.repo

import com.lumentv.data.db.MovieEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.xtream.XVodInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A film's page details (summary, length, genre, backdrop) asked to the provider once per film and kept for the
 * session: the banner of the Films screen follows the focused poster, so the same film is asked again and again.
 */
class Artwork(private val playlists: PlaylistRepository) {
    private val movies = object : LinkedHashMap<Long, XVodInfo?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, XVodInfo?>?) = size > 400
    }

    fun cached(m: MovieEntity): XVodInfo? = synchronized(movies) { movies[m.id] }

    suspend fun movie(m: MovieEntity): XVodInfo? {
        synchronized(movies) { if (movies.containsKey(m.id)) return movies[m.id] }
        val p = playlists.get(m.playlistId) ?: return null
        if (p.type != PlaylistType.XTREAM || m.streamId <= 0) return null
        val info = withContext(Dispatchers.IO) { runCatching { playlists.xtream(p).vodInfo(m.streamId) }.getOrNull() }
        synchronized(movies) { movies[m.id] = info }
        return info
    }
}
