package com.lumentv.desktop

import com.lumentv.core.Featured
import com.lumentv.core.asHttp
import com.lumentv.core.Resume
import com.lumentv.data.TitleLogos
import com.lumentv.data.Tmdb
import com.lumentv.data.discover.DiscoverEngine
import com.lumentv.data.discover.DiscoverInput
import com.lumentv.data.discover.Rated
import com.lumentv.data.discover.TitleIndex
import com.lumentv.data.discover.TitleSearch
import com.lumentv.data.discover.VodLite
import com.lumentv.sync.ProfileMerge
import com.lumentv.sync.SyncRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The films and series of a catalog, light, for the rows and the search ([TitleIndex], shared with the TV). Ids are list positions. */
class CatalogIndex(val catalog: Catalog) {
    val titles = TitleIndex(
        catalog.movies.mapIndexed { i, m -> VodLite(i.toLong(), m.uid, m.name, m.category, m.year, m.rating) },
        catalog.series.mapIndexed { i, s -> VodLite(i.toLong(), s.uid, s.name, s.category, s.year, s.rating) },
    )
    fun movie(v: VodLite): Mv? = catalog.movies.getOrNull(v.id.toInt())
    fun series(v: VodLite): Se? = catalog.series.getOrNull(v.id.toInt())
    val channels = catalog.channels.map { com.lumentv.core.SmartSearch.Entry(it, it.name) }
}

/** A row of the Films / Séries home on the PC. */
class Shelf<T>(val title: String, val items: List<T>)

/**
 * TMDB on the PC, as on the TV: the rows of the homes (same engine), logos, ratings, French summaries and cast,
 * the search by title and by person, and the copies of a film. Personal ratings live in the profile (shared with the TV).
 */
class Vod(private val state: AppState) {
    /** TMDB's requests: with their disk cache and retries (TmdbHttp), shared by the logos. */
    val tmdbHttp = com.lumentv.data.TmdbHttp.client(state.http, Store.dir)
    val tmdb = Tmdb(tmdbHttp.asHttp(), BuildInfo.TMDB_KEY)
    /** Logos, ratings, summaries and cast, through TMDB's client (its disk cache). */
    val logos = TitleLogos(tmdbHttp.asHttp(), BuildInfo.TMDB_KEY)
    private val engine = DiscoverEngine(tmdb)
    private var built: CatalogIndex? = null
    private val links = HashMap<String, String?>()

    /** The index of the shown catalog, built again when the catalog changes. */
    fun index(c: Catalog): CatalogIndex = built?.takeIf { it.catalog === c } ?: CatalogIndex(c).also { built = it }

    fun movieRows(c: Catalog): Flow<List<Shelf<Mv>>> {
        val idx = index(c)
        return engine.rows(input(false, idx)).map { rows -> rows.map { r -> Shelf(r.title, r.items.mapNotNull(idx::movie).take(DiscoverEngine.ROW)) }.filter { DiscoverEngine.keep(it.title, it.items.size) } }
    }

    fun seriesRows(c: Catalog): Flow<List<Shelf<Se>>> {
        val idx = index(c)
        return engine.rows(input(true, idx)).map { rows -> rows.map { r -> Shelf(r.title, r.items.mapNotNull(idx::series).take(DiscoverEngine.ROW)) }.filter { DiscoverEngine.keep(it.title, it.items.size) } }
    }

    private fun input(series: Boolean, idx: CatalogIndex): DiscoverInput {
        val c = idx.catalog
        val recent = if (series) c.series.withIndex().sortedByDescending { it.value.updated }.take(200).map { (i, s) -> VodLite(i.toLong(), s.uid, s.name, s.category, s.year, s.rating) }
        else c.movies.withIndex().sortedByDescending { it.value.added }.take(200).map { (i, m) -> VodLite(i.toLong(), m.uid, m.name, m.category, m.year, m.rating) }
        // uid → how much of it was watched (0..1), newest first; a series: by its episodes seen to the end.
        val watched = LinkedHashMap<String, Double>()
        if (series) state.historyOf(Kind.EPISODE).filter { it.parentUid != null }.groupBy { it.parentUid!! }
            .forEach { (uid, eps) -> watched[uid] = (eps.count { Resume.watched(it.positionMs, it.durationMs) } / 3.0).coerceIn(0.3, 1.0) }
        else state.historyOf(Kind.MOVIE).forEach { h -> watched[h.uid] = if (h.durationMs > 0) (h.positionMs.toDouble() / h.durationMs).coerceIn(0.0, 1.0) else 0.5 }
        val kind = if (series) Kind.SERIES else Kind.MOVIE
        val rated = state.ratings(kind).map { Rated(it.uid, it.stars, it.ratedAt) }
        return DiscoverInput(series, idx.titles, recent, rated, watched, link = { v -> link(series, v, if (series) idx.series(v)?.tmdb else null) })
    }

    /** The TMDB id of a playlist title: the provider's when it gives one, else a search by title and year (kept for the session). */
    private suspend fun link(series: Boolean, v: VodLite, provided: String?): String? = provided ?: withContext(Dispatchers.IO) {
        val key = (if (series) "se:" else "mv:") + v.uid
        synchronized(links) { if (key in links) return@withContext links[key] }
        val id = runCatching { tmdb.idOf(series, Featured.shortTitle(v.name), Featured.yearOf(v.year, v.name)) }.getOrNull()
        synchronized(links) { links[key] = id }
        id
    }

    /** Logo, TMDB rating, French summary, cast and director / creators of a title (null without TMDB). */
    suspend fun info(series: Boolean, name: String, tmdbId: String?, year: Int?): TitleLogos.Info? =
        if (!logos.enabled) null else withContext(Dispatchers.IO) { runCatching { logos.info(series, tmdbId, Featured.shortTitle(name), year) }.getOrNull() }

    fun cachedInfo(series: Boolean, name: String, tmdbId: String?, year: Int?): TitleLogos.Info? =
        if (!logos.enabled) null else logos.cached(series, tmdbId, Featured.shortTitle(name), year)

    /** Films, series and people for the search. */
    suspend fun search(query: String, c: Catalog): TitleSearch.Results = withContext(Dispatchers.IO) { TitleSearch.search(query, index(c).titles, tmdb) }

    /** The other copies of a film (4K, VF, VOSTFR…), the given one included, in the playlist's order. */
    fun versions(c: Catalog, m: Mv): List<Mv> {
        val base = Featured.baseTitle(m.name)
        if (base.length < 2) return listOf(m)
        // The French copies first, then the subtitled and the others; the best quality first in each.
        return c.movies.filter { Featured.baseTitle(it.name) == base && (it == m || !Featured.foreign(it.name, it.category)) }
            .sortedWith(compareBy({ com.lumentv.core.SmartSearch.languageRank(it.name) }, { com.lumentv.core.SmartSearch.qualityRank(it.name) }))
            .ifEmpty { listOf(m) }
    }
}

// ---- Personal ratings (in the profile, shared with the TV) -------------------------------------------------------

fun AppState.rating(kind: String, uid: String): Int = activeKeyOrFirst?.let { k ->
    doc.ratings[ProfileMerge.itemKey(k, kind, uid)]?.takeIf { !it.deleted }?.stars
} ?: 0

fun AppState.ratings(kind: String): List<SyncRating> {
    val k = activeKeyOrFirst ?: return emptyList()
    return doc.ratings.values.filter { it.playlist == k && it.kind == kind && !it.deleted }
}

/** [stars] 1 to 5; 0 takes the rating away. */
fun AppState.rate(kind: String, uid: String, stars: Int, title: String, image: String?) {
    val k = activeKeyOrFirst ?: return
    val key = ProfileMerge.itemKey(k, kind, uid)
    val now = System.currentTimeMillis()
    val r = if (stars in 1..5) SyncRating(k, kind, uid, stars, now, title, image, at = now)
        else doc.ratings[key]?.copy(deleted = true, at = now) ?: return
    updateDoc { it.copy(ratings = it.ratings + (key to r)) }
    show(if (stars in 1..5) "Votre note : $stars ★" else "Note retirée")
}
