package com.lumentv.data.repo

import com.lumentv.core.Featured
import com.lumentv.core.SmartSearch
import com.lumentv.data.Tmdb
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.ChannelLite
import com.lumentv.data.db.VodLite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The whole library of a playlist, light (names only), in memory: for the search (prepared titles) and to find the
 * playlist's copy of a TMDB title (by its French or original title, ignoring spaces and dashes, year ± 1). Built once
 * per catalog refresh, in the background; about 2 MB per 10 000 titles.
 */
class LibraryIndex(private val db: AppDatabase) {
    class Index(
        val movies: List<SmartSearch.Entry<VodLite>>, val series: List<SmartSearch.Entry<VodLite>>, val channels: List<SmartSearch.Entry<ChannelLite>>,
        private val moviesByTitle: Map<String, List<VodLite>>, private val seriesByTitle: Map<String, List<VodLite>>,
    ) {
        /** The playlist's copy of a TMDB title (the best rated copy when there are several), or null. */
        fun find(series: Boolean, title: String, original: String, year: Int?): VodLite? {
            val map = if (series) seriesByTitle else moviesByTitle
            val candidates = (map[key(title)].orEmpty() + map[key(original)].orEmpty()).distinctBy { it.id }
            if (candidates.isEmpty()) return null
            val sameYear = candidates.filter { c -> val y = Featured.yearOf(c.year, c.name); y == null || year == null || kotlin.math.abs(y - year) <= 1 }
            return sameYear.firstOrNull()
        }

        fun find(series: Boolean, item: Tmdb.Item) = find(series, item.title, item.original, item.year)
    }

    private val lock = Mutex()
    private val built = HashMap<Long, Pair<Long, Index>>()

    /** The index of [pid], built again after a refresh of the playlist. */
    suspend fun of(pid: Long): Index = lock.withLock {
        val stamp = db.playlists().get(pid)?.lastRefresh ?: 0
        built[pid]?.let { (s, i) -> if (s == stamp) return@withLock i }
        val i = withContext(Dispatchers.Default) {
            val movies = withContext(Dispatchers.IO) { db.vod().movieIndex(pid) }.filter { !Featured.foreign(it.name, it.category) }
            val series = withContext(Dispatchers.IO) { db.vod().seriesIndex(pid) }.filter { !Featured.foreign(it.name, it.category) }
            val channels = withContext(Dispatchers.IO) { db.channels().channelIndex(pid) }
            Index(
                movies.map { SmartSearch.Entry(it, it.name) }, series.map { SmartSearch.Entry(it, it.name) }, channels.map { SmartSearch.Entry(it, it.name) },
                movies.groupBy { key(it.name) }, series.groupBy { key(it.name) },
            )
        }
        built[pid] = stamp to i
        i
    }

    companion object {
        /** "Spider-Man : No Way Home (2021) 4K" and "Spider-Man: No Way Home" → "spidermannowayhome". */
        fun key(title: String) = Featured.baseTitle(title).replace(" ", "")
    }
}
