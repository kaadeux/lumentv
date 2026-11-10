package com.lumentv.data.repo

import com.lumentv.core.IoDispatcher
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
    class Index(movies: List<VodLite>, series: List<VodLite>, channelList: List<ChannelLite>) : TitleIndex(movies, series) {
        /** Prepared at the first search only: 56 000 channels took most of the time before the Films page could show. */
        val channels: List<SmartSearch.Entry<ChannelLite>> by lazy { channelList.map { SmartSearch.Entry(it, it.name) } }
    }

    private val lock = Mutex()
    private val built = HashMap<Long, Pair<Long, Index>>()

    /** The index of [pid], built again after a refresh of the playlist. */
    suspend fun of(pid: Long): Index = lock.withLock {
        val stamp = db.playlists().get(pid)?.lastRefresh ?: 0
        built[pid]?.let { (s, i) -> if (s == stamp) return@withLock i }
        val i = withContext(Dispatchers.Default) {
            val movies = withContext(IoDispatcher) { db.vod().movieIndex(pid).map { it.lite() } }
            val series = withContext(IoDispatcher) { db.vod().seriesIndex(pid).map { it.lite() } }
            val channels = withContext(IoDispatcher) { db.channels().channelIndex(pid) }
            Index(movies, series, channels)
        }
        // Only the list in use: two big lists side by side ran the TV out of memory.
        built.clear()
        built[pid] = stamp to i
        i
    }
}
