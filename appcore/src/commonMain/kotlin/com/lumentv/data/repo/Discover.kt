package com.lumentv.data.repo

import com.lumentv.core.nowMillis
import com.lumentv.core.IoDispatcher
import com.lumentv.core.Resume
import com.lumentv.data.Tmdb
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PrefKind
import com.lumentv.data.db.RatingEntity
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.db.TmdbLinkEntity
import com.lumentv.data.discover.DiscoverEngine
import com.lumentv.data.discover.DiscoverInput
import com.lumentv.data.discover.Rated
import com.lumentv.data.discover.Row
import com.lumentv.data.discover.SeeAll
import com.lumentv.data.discover.VodLite
import com.lumentv.data.repo.LibraryRepository.Shelf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The rows of the Films and Séries homes on this device: the playlist, history and ratings from the database, given
 * to [DiscoverEngine] (shared with the PC, which explains the rows), then the full film / series rows of each title.
 */
class Discover(private val db: AppDatabase, private val tmdb: Tmdb, private val index: LibraryIndex, private val playlists: PlaylistRepository) {
    private val engine = DiscoverEngine(tmdb)

    fun movies(pid: Long, history: List<HistoryEntity>, ratings: List<RatingEntity>): Flow<List<Shelf<MovieRow>>> =
        rows(false, pid, history, ratings).map { rows ->
            val all = rows.flatMap { r -> r.items.map { it.id } }.distinct().chunked(500).flatMap { db.vod().moviesByIds(it) }.associateBy { it.m.id }
            shelves(rows, { all[it.id] }) { l -> val byId = db.vod().moviesByIds(l.map { it.id }).associateBy { it.m.id }; l.mapNotNull { byId[it.id] } }
        }

    fun series(pid: Long, history: List<HistoryEntity>, ratings: List<RatingEntity>): Flow<List<Shelf<SeriesRow>>> =
        rows(true, pid, history, ratings).map { rows ->
            val all = rows.flatMap { r -> r.items.map { it.id } }.distinct().chunked(500).flatMap { db.vod().seriesByIds(it) }.associateBy { it.s.id }
            shelves(rows, { all[it.id] }) { l -> val byId = db.vod().seriesByIds(l.map { it.id }).associateBy { it.s.id }; l.mapNotNull { byId[it.id] } }
        }

    /** [fetch]: the titles of a "Tout voir" page read from the database, in their order. */
    private fun <T : Any> shelves(rows: List<Row>, full: (VodLite) -> T?, fetch: suspend (List<VodLite>) -> List<T>): List<Shelf<T>> =
        rows.map { r ->
            val all = r.items.mapNotNull(full)
            Shelf(r.title, all.take(DiscoverEngine.ROW), r.badges, (r.more ?: SeeAll(emptyList(), r.items)).map({ fetch(it) }, all))
        }.filter { DiscoverEngine.keep(it.title, it.items.size) }

    private fun rows(series: Boolean, pid: Long, history: List<HistoryEntity>, ratings: List<RatingEntity>): Flow<List<Row>> = flow {
        val kind = if (series) "se" else HistoryKind.MOVIE
        // The index is the long part on a big list: built while the rest is read, not after.
        val indexed = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).async { index.of(pid) }
        val hidden = db.prefs().allOf(pid, if (series) PrefKind.SERIES_CATEGORY else PrefKind.MOVIE_CATEGORY).filter { it.hidden }.mapTo(HashSet()) { it.uid }
        val recent = if (series) db.vod().recentSeries(pid, 200).map { VodLite(it.s.id, it.s.uid, it.s.name, it.s.category, it.s.year, it.s.rating) }
        else db.vod().recentMovies(pid, 200).map { VodLite(it.m.id, it.m.uid, it.m.name, it.m.category, it.m.year, it.m.rating) }
        // uid → how much of it was watched (0..1), newest first; a series: by its episodes seen to the end.
        val watched = LinkedHashMap<String, Double>()
        if (series) history.filter { it.kind == HistoryKind.EPISODE && it.playlistId == pid && it.parentUid != null }
            .groupBy { it.parentUid!! }.forEach { (uid, eps) -> watched[uid] = (eps.count { Resume.watched(it.positionMs, it.durationMs) } / 3.0).coerceIn(0.3, 1.0) }
        else history.filter { it.kind == HistoryKind.MOVIE && it.playlistId == pid }
            .forEach { h -> watched[h.uid] = if (h.durationMs > 0) (h.positionMs.toDouble() / h.durationMs).coerceIn(0.0, 1.0) else 0.5 }
        val input = DiscoverInput(series, indexed.await(), recent,
            ratings.filter { it.playlistId == pid && it.kind == kind }.map { Rated(it.uid, it.stars, it.ratedAt) }, watched, hidden,
            { link(series, pid, it) }, if (series) ({ newEpisodes(pid, history) }) else null)
        emitAll(engine.rows(input))
    }.flowOn(IoDispatcher)

    /**
     * The series followed that have something new, with what to say on the poster: the last episode watched (the
     * furthest, seen to the end), and the playlist has episodes after it. Xtream lists give their episodes only per
     * series: asked again (and cached) only for a series the provider changed since it was watched, a few at a time.
     */
    private suspend fun newEpisodes(pid: Long, history: List<HistoryEntity>): List<Pair<VodLite, String>> = kotlinx.coroutines.coroutineScope {
        val p = db.playlists().get(pid) ?: return@coroutineScope emptyList()
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        history.filter { it.kind == HistoryKind.EPISODE && it.playlistId == pid && it.parentUid != null }
            .groupBy { it.parentUid!! }.entries.sortedByDescending { (_, eps) -> eps.maxOf { it.watchedAt } }.take(30)
            .map { (uid, seen) -> async {
                val row = db.vod().seriesByUid(pid, uid) ?: return@async null
                var eps = db.vod().episodes(pid, uid)
                val lastWatch = seen.maxOf { it.watchedAt }
                if (p.type == com.lumentv.data.db.PlaylistType.XTREAM && row.s.seriesId > 0 && (eps.isEmpty() || row.s.updatedAt > lastWatch))
                    eps = runCatching { gate.withPermit { playlists.loadXtreamEpisodes(p, uid, row.s.seriesId) } }.getOrDefault(eps)
                val byUid = eps.associateBy { it.uid }
                val furthest = seen.mapNotNull { h -> byUid[h.uid]?.let { it to h } }
                    .maxWithOrNull(compareBy({ it.first.season }, { it.first.episode })) ?: return@async null
                // Caught up: the furthest episode was watched to the end, and some come after it.
                if (!Resume.watched(furthest.second.positionMs, furthest.second.durationMs)) return@async null
                val (s, e) = furthest.first.season to furthest.first.episode
                val after = eps.filter { it.season > s || (it.season == s && it.episode > e) }
                if (after.isEmpty()) return@async null
                val badge = com.lumentv.data.discover.NewEpisodes.badge(s, after.map { it.season })
                Triple(VodLite(row.s.id, row.s.uid, row.s.name, row.s.category, row.s.year, row.s.rating), badge, maxOf(row.s.updatedAt, lastWatch))
            } }.awaitAll().filterNotNull().sortedByDescending { it.third }.map { it.first to it.second }
    }

    /** The TMDB id of a playlist title: the provider's when it gives one, else a search by title and year; kept in the database. */
    private suspend fun link(series: Boolean, pid: Long, v: VodLite): String? {
        val key = (if (series) "se" else "mv") + ":$pid:${v.uid}"
        db.ratings().link(key)?.let { if (it.tmdbId != null || nowMillis() - it.checkedAt < 7 * 86_400_000L) return it.tmdbId }
        val provided = if (series) db.vod().seriesByUid(pid, v.uid)?.s?.tmdb else null
        val id = provided ?: tmdb.idOf(series, com.lumentv.core.Featured.shortTitle(v.name), com.lumentv.core.Featured.yearOf(v.year, v.name))
        db.ratings().saveLink(TmdbLinkEntity(key, id, nowMillis()))
        return id
    }
}
