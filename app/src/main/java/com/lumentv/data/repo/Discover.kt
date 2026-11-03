package com.lumentv.data.repo

import com.lumentv.core.Featured
import com.lumentv.core.normalize
import com.lumentv.data.Tmdb
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PrefKind
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.repo.LibraryRepository.Shelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The rows of the Films and Séries homes, from TMDB: what people watch this week, what is popular in France, the best
 * rated, and recommendations from what the family watched. Every row only keeps titles the playlist has (one copy per
 * title). Without the TMDB key, or when it does not answer, the rows built from the playlist alone are used.
 *
 * "Recommandé pour vous": TMDB's recommendations for the last titles watched, each weighted by how recent the
 * viewing is and how high the title comes in its list; a title recommended by several viewings goes up. What was
 * already watched is left out.
 */
class Discover(private val db: AppDatabase, private val tmdb: Tmdb, private val library: LibraryRepository) {
    private var movieIndex: Pair<String, Map<String, List<MovieRow>>>? = null
    private var seriesIndex: Pair<String, Map<String, List<SeriesRow>>>? = null

    suspend fun movies(pid: Long, history: List<HistoryEntity>): List<Shelf<MovieRow>> = withContext(Dispatchers.IO) {
        val lastMovie = history.firstOrNull { it.kind == HistoryKind.MOVIE && it.playlistId == pid }?.uid
        if (!tmdb.enabled) return@withContext library.featuredMovies(pid, lastMovie)
        val index = movies(pid)
        fun match(items: List<Tmdb.Item>) = items.mapNotNull { find(index, it) { r -> Featured.yearOf(r.m.year, r.m.name) } }.distinctBy { it.m.id }.take(Featured.ROW)
        val watched = history.filter { it.kind == HistoryKind.MOVIE && it.playlistId == pid }
        val seen = watched.map { it.uid }.toSet()
        val recent = watched.take(8).mapNotNull { h -> db.vod().movieByUid(pid, h.uid) }
        coroutineScope {
            val trending = async { tmdb.list("trending/movie/week", 3) }
            val popular = async { tmdb.list("movie/popular", 3, "region" to "FR") }
            val top = async { tmdb.list("movie/top_rated", 5) }
            val recs = recent.map { m -> async { tmdb.idOf(false, m.m.name.substringBefore(" (").trim(), Featured.yearOf(m.m.year, m.m.name))?.let { tmdb.recommendations(false, it) }.orEmpty() } }
            val lists = recs.awaitAll()
            val rows = listOf(
                Shelf("Recommandé pour vous", match(recommended(lists)).filter { it.m.uid !in seen }),
                Shelf("Tendances de la semaine", match(trending.await())),
                Shelf("Nouveautés", Featured.clean(db.vod().recentMovies(pid, 200), { it.m.name }, { it.m.category }, hidden(pid, PrefKind.MOVIE_CATEGORY))),
                Shelf("Populaires en France", match(popular.await())),
                Shelf("Les mieux notés", match(top.await())),
            ) + listOfNotNull(recent.firstOrNull()?.let { last ->
                Shelf("Parce que vous avez regardé ${last.m.name.substringBefore(" (").trim()}", match(lists.firstOrNull().orEmpty()).filter { it.m.uid !in seen })
            })
            rows.filter { it.items.size >= 3 }.ifEmpty { library.featuredMovies(pid, lastMovie) }
        }
    }

    suspend fun series(pid: Long, history: List<HistoryEntity>): List<Shelf<SeriesRow>> = withContext(Dispatchers.IO) {
        val lastSeries = history.firstOrNull { it.kind == HistoryKind.EPISODE && it.playlistId == pid }?.parentUid
        if (!tmdb.enabled) return@withContext library.featuredSeries(pid, lastSeries)
        val index = series(pid)
        fun match(items: List<Tmdb.Item>) = items.mapNotNull { find(index, it) { r -> Featured.year(r.s.year) } }.distinctBy { it.s.id }.take(Featured.ROW)
        val watchedUids = history.filter { it.kind == HistoryKind.EPISODE && it.playlistId == pid }.mapNotNull { it.parentUid }.distinct()
        val recent = watchedUids.take(8).mapNotNull { db.vod().seriesByUid(pid, it) }
        coroutineScope {
            val trending = async { tmdb.list("trending/tv/week", 3) }
            val popular = async { tmdb.list("tv/popular", 3) }
            val top = async { tmdb.list("tv/top_rated", 5) }
            val recs = recent.map { s -> async { (s.s.tmdb ?: tmdb.idOf(true, s.s.name, Featured.year(s.s.year)))?.let { tmdb.recommendations(true, it) }.orEmpty() } }
            val lists = recs.awaitAll()
            val seen = watchedUids.toSet()
            val rows = listOf(
                Shelf("Recommandé pour vous", match(recommended(lists)).filter { it.s.uid !in seen }),
                Shelf("Tendances de la semaine", match(trending.await())),
                Shelf("Nouveautés", Featured.clean(db.vod().recentSeries(pid, 200), { it.s.name }, { it.s.category }, hidden(pid, PrefKind.SERIES_CATEGORY))),
                Shelf("Populaires", match(popular.await())),
                Shelf("Les mieux notées", match(top.await())),
            ) + listOfNotNull(recent.firstOrNull()?.let { last ->
                Shelf("Parce que vous avez regardé ${last.s.name}", match(lists.firstOrNull().orEmpty()).filter { it.s.uid !in seen })
            })
            rows.filter { it.items.size >= 3 }.ifEmpty { library.featuredSeries(pid, lastSeries) }
        }
    }

    /** Recommendations of each viewing (newest first) merged: recent viewings and high places weigh more. */
    private fun recommended(lists: List<List<Tmdb.Item>>): List<Tmdb.Item> {
        val score = HashMap<String, Double>()
        val byId = HashMap<String, Tmdb.Item>()
        lists.forEachIndexed { i, list ->
            val w = 1.0 / (1 + i * 0.6)
            list.forEachIndexed { pos, it -> byId[it.id] = it; score[it.id] = (score[it.id] ?: 0.0) + w * (1.0 - pos / 40.0) }
        }
        return score.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }
    }

    /** The playlist's copy of a TMDB title: same name (French or original), same year give or take one. */
    private fun <T> find(index: Map<String, List<T>>, item: Tmdb.Item, year: (T) -> Int?): T? {
        val candidates = index[normalize(item.title)].orEmpty() + index[normalize(item.original)].orEmpty()
        val iy = item.year
        return candidates.firstOrNull { c -> val y = year(c); y == null || iy == null || kotlin.math.abs(y - iy) <= 1 }
    }

    private suspend fun hidden(pid: Long, kind: String) = db.prefs().allOf(pid, kind).filter { it.hidden }.mapTo(HashSet()) { it.uid }

    /** The playlist's films by title, once per catalog (the hidden categories and foreign copies left out). */
    private suspend fun movies(pid: Long): Map<String, List<MovieRow>> {
        val stamp = "$pid:" + db.playlists().get(pid)?.lastRefresh
        movieIndex?.let { (k, m) -> if (k == stamp) return m }
        val hidden = hidden(pid, PrefKind.MOVIE_CATEGORY)
        val m = db.vod().movies(pid, null).filter { !Featured.foreign(it.m.name, it.m.category) && it.m.category !in hidden }
            .groupBy { Featured.baseTitle(it.m.name) }
        movieIndex = stamp to m
        return m
    }

    private suspend fun series(pid: Long): Map<String, List<SeriesRow>> {
        val stamp = "$pid:" + db.playlists().get(pid)?.lastRefresh
        seriesIndex?.let { (k, m) -> if (k == stamp) return m }
        val hidden = hidden(pid, PrefKind.SERIES_CATEGORY)
        val m = db.vod().series(pid, null).filter { !Featured.foreign(it.s.name, it.s.category) && it.s.category !in hidden }
            .groupBy { Featured.baseTitle(it.s.name) }
        seriesIndex = stamp to m
        return m
    }
}
