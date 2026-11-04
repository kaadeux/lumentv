package com.lumentv.data.repo

import com.lumentv.core.SmartSearch
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.ChannelLite
import com.lumentv.data.discover.TitleIndex
import com.lumentv.data.discover.VodLite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The whole library of a playlist, light (names only), in memory: for the search (prepared titles) and to find the
 * playlist's copy of a TMDB title ([TitleIndex], shared with the PC). Built once per catalog refresh, in the
 * background; about 2 MB per 10 000 titles.
 */
class LibraryIndex(private val db: AppDatabase) {
    class Index(movies: List<VodLite>, series: List<VodLite>, val channels: List<SmartSearch.Entry<ChannelLite>>) : TitleIndex(movies, series)

    private val lock = Mutex()
    private val built = HashMap<Long, Pair<Long, Index>>()

    /** The index of [pid], built again after a refresh of the playlist. */
    suspend fun of(pid: Long): Index = lock.withLock {
        val stamp = db.playlists().get(pid)?.lastRefresh ?: 0
        built[pid]?.let { (s, i) -> if (s == stamp) return@withLock i }
        val i = withContext(Dispatchers.Default) {
            val movies = withContext(Dispatchers.IO) { db.vod().movieIndex(pid) }
            val series = withContext(Dispatchers.IO) { db.vod().seriesIndex(pid) }
            val channels = withContext(Dispatchers.IO) { db.channels().channelIndex(pid) }
            Index(movies, series, channels.map { SmartSearch.Entry(it, it.name) })
        }
        built[pid] = stamp to i
        i
    }
}
