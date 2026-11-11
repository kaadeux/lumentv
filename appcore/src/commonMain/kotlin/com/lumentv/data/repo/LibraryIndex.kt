package com.lumentv.data.repo

import com.lumentv.core.IoDispatcher
import com.lumentv.core.SmartSearch
import com.lumentv.core.logWarn
import com.lumentv.core.nowMillis
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.ChannelLite
import com.lumentv.data.db.TitleEntity
import com.lumentv.data.db.transaction
import com.lumentv.data.discover.TitleIndex
import com.lumentv.data.discover.VodLite
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The whole library of a playlist, light (names only), in memory: for the search (prepared titles) and to find the
 * playlist's copy of a TMDB title ([TitleIndex], shared with the PC). Built once per catalog refresh and kept in the
 * titles table: the next starts read it as it is. About 2 MB per 10 000 titles.
 */
class LibraryIndex(private val db: AppDatabase) {
    class Index(prepared: TitleIndex.Prepared, private val loadChannels: suspend () -> List<ChannelLite>) : TitleIndex(prepared) {
        private val channelLock = Mutex()
        private var channelEntries: List<SmartSearch.Entry<ChannelLite>>? = null

        /** Read and prepared at the first search only: 56 000 channels took most of the time before the Films page could show. */
        suspend fun channels(): List<SmartSearch.Entry<ChannelLite>> = channelLock.withLock {
            channelEntries ?: loadChannels().map { SmartSearch.Entry(it, it.name) }.also { channelEntries = it }
        }
    }

    private val lock = Mutex()
    private val built = HashMap<Long, Pair<Long, Index>>()

    /** The index of [pid], built again after a refresh of the playlist. */
    suspend fun of(pid: Long): Index = lock.withLock {
        val stamp = db.playlists().get(pid)?.lastRefresh ?: 0
        built[pid]?.let { (s, i) -> if (s == stamp) return@withLock i }
        val t0 = nowMillis()
        val stored = withContext(IoDispatcher) { db.titles().of(pid, stamp) }
        val prepared = if (stored.isNotEmpty()) {
            TitleIndex.Prepared(stored.filter { !it.series }.map { it.entry() }, stored.filter { it.series }.map { it.entry() })
        } else {
            prepare(pid).also { p -> save(pid, stamp, p) }
        }
        val i = Index(prepared) { withContext(IoDispatcher) { db.channels().channelIndex(pid) } }
        logWarn("Playlists", "index $pid : ${i.movies.size + i.series.size} titres en ${nowMillis() - t0} ms" + if (stored.isEmpty()) " (préparés)" else "")
        // Only the list in use: two big lists side by side ran the TV out of memory.
        built.clear()
        built[pid] = stamp to i
        i
    }

    /**
     * Every title of the list read and prepared: films and series read at once (one connection each), their titles
     * cleaned on every core. 20 s on a TV one after the other, the Films rows waiting.
     */
    private suspend fun prepare(pid: Long): TitleIndex.Prepared = coroutineScope {
        val movies = async(IoDispatcher) { db.vod().movieIndex(pid).map { it.lite() } }
        val series = async(IoDispatcher) { db.vod().seriesIndex(pid).map { it.lite() } }
        val m = async { TitleIndex.prepareParallel(movies.await()) }
        val s = async { TitleIndex.prepareParallel(series.await()) }
        TitleIndex.Prepared(m.await(), s.await())
    }

    /** Kept for the next starts; not kept is no harm (prepared again then). */
    private suspend fun save(pid: Long, stamp: Long, p: TitleIndex.Prepared) = withContext(IoDispatcher) {
        fun rows(list: List<SmartSearch.Entry<VodLite>>, series: Boolean) = list.map { e ->
            val v = e.item
            TitleEntity(playlistId = pid, stamp = stamp, series = series, vodId = v.id, uid = v.uid, name = v.name, category = v.category,
                year = v.year, rating = v.rating, norm = e.norm, otherLanguage = e.otherLanguage, english = e.english)
        }
        runCatching {
            db.transaction {
                db.titles().deleteFor(pid)
                (rows(p.movies, false) + rows(p.series, true)).chunked(500).forEach { db.titles().insert(it) }
            }
        }.onFailure { logWarn("Playlists", "index non gardé : ${it::class.simpleName}") }
    }

    private fun TitleEntity.entry() =
        SmartSearch.Entry(VodLite(vodId, uid, name, category, year, rating), norm, otherLanguage, english)
}
