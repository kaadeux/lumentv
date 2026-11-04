package com.lumentv.data.repo

import com.lumentv.core.Featured
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
import com.lumentv.data.db.VodLite
import com.lumentv.data.repo.LibraryRepository.Shelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The rows of the Films and Séries homes. Every row only keeps titles the playlist really has (found through
 * [LibraryIndex]); TMDB only says what to put forward. Three steps, each shown as soon as it is ready:
 *
 * 1. from the playlist alone: "Ajoutés récemment", "Vos notes";
 * 2. TMDB and the viewer's taste: "Recommandé pour vous", trending, "Parce que vous avez aimé…", new releases, popular,
 *    "Dans vos genres préférés", "À découvrir", best rated, "Vous pourriez aussi aimer";
 * 3. one row per genre and per streaming service (available in France according to TMDB / JustWatch).
 *
 * The taste comes from the personal ratings (5 stars = loved, 1 = disliked) and, before there are any, from what was
 * watched (to the end counts more than started). Liked titles bring their TMDB recommendations and similar titles,
 * their genres, actors and directors weigh on every candidate; disliked ones push their genres down. Watched or rated
 * titles are never proposed, and a title appears once per row.
 */
class Discover(private val db: AppDatabase, private val tmdb: Tmdb, private val index: LibraryIndex) {
    private val gate = Semaphore(6)
    private suspend fun <T> limited(block: () -> T): T = gate.withPermit { block() }

    fun movies(pid: Long, history: List<HistoryEntity>, ratings: List<RatingEntity>): Flow<List<Shelf<MovieRow>>> =
        rows(false, pid, history, ratings).map { toMovies(it) }

    fun series(pid: Long, history: List<HistoryEntity>, ratings: List<RatingEntity>): Flow<List<Shelf<SeriesRow>>> =
        rows(true, pid, history, ratings).map { toSeries(it) }

    private suspend fun toMovies(rows: List<Shelf<VodLite>>): List<Shelf<MovieRow>> {
        val all = rows.flatMap { r -> r.items.map { it.id } }.distinct().chunked(500).flatMap { db.vod().moviesByIds(it) }.associateBy { it.m.id }
        return rows.map { r -> Shelf(r.title, r.items.mapNotNull { all[it.id] }.take(ROW)) }.filter { it.items.size >= MIN_ROW || (it.title == MY_RATINGS && it.items.isNotEmpty()) }
    }

    private suspend fun toSeries(rows: List<Shelf<VodLite>>): List<Shelf<SeriesRow>> {
        val all = rows.flatMap { r -> r.items.map { it.id } }.distinct().chunked(500).flatMap { db.vod().seriesByIds(it) }.associateBy { it.s.id }
        return rows.map { r -> Shelf(r.title, r.items.mapNotNull { all[it.id] }.take(ROW)) }.filter { it.items.size >= MIN_ROW || (it.title == MY_RATINGS && it.items.isNotEmpty()) }
    }

    private fun rows(series: Boolean, pid: Long, history: List<HistoryEntity>, ratings: List<RatingEntity>): Flow<List<Shelf<VodLite>>> = flow {
        val idx = index.of(pid)
        val kind = if (series) "se" else HistoryKind.MOVIE
        val myRatings = ratings.filter { it.playlistId == pid && it.kind == kind }
        val hidden = db.prefs().allOf(pid, if (series) PrefKind.SERIES_CATEGORY else PrefKind.MOVIE_CATEGORY).filter { it.hidden }.mapTo(HashSet()) { it.uid }
        val byUid = (if (series) idx.series else idx.movies).associateBy({ it.item.uid }, { it.item })
        fun ok(v: VodLite) = v.category !in hidden && !Featured.foreign(v.name, v.category)

        // 1. The playlist alone
        val recent = if (series) db.vod().recentSeries(pid, 200).map { VodLite(it.s.id, it.s.uid, it.s.name, it.s.category, it.s.year, it.s.rating) }
        else db.vod().recentMovies(pid, 200).map { VodLite(it.m.id, it.m.uid, it.m.name, it.m.category, it.m.year, it.m.rating) }
        val local = listOf(
            Shelf(if (series) "Ajoutées récemment" else "Ajoutés récemment", one(recent.filter(::ok))),
            Shelf(MY_RATINGS, myRatings.sortedWith(compareByDescending<RatingEntity> { it.stars }.thenByDescending { it.ratedAt }).mapNotNull { byUid[it.uid] }),
        )
        emit(local)
        if (!tmdb.enabled) return@flow

        // 2. TMDB and the taste
        val seen = HashSet<String>() // uids watched or rated: never proposed
        val seeds = seeds(series, pid, history, myRatings, byUid, seen)
        val type = if (series) "tv" else "movie"
        val region = if (series) emptyArray() else arrayOf("region" to "FR")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        fun pick(items: List<Tmdb.Item>, skip: Set<Long> = emptySet()) =
            one(items.mapNotNull { idx.find(series, it) }.filter { ok(it) && it.uid !in seen && it.id !in skip })

        val taste = coroutineScope {
            val trending = async { limited { tmdb.list("trending/$type/week", 5) } }
            val fresh = async { limited { tmdb.list(if (series) "tv/on_the_air" else "movie/now_playing", 5, *region) } }
            val popular = async { limited { tmdb.list("$type/popular", 5, *region) } }
            val top = async { limited { tmdb.list("$type/top_rated", 6) } }
            val details = seeds.map { s -> async { s to limited { tmdb.details(series, s.tmdbId) } } }.awaitAll()
                .mapNotNull { (s, d) -> d?.let { s to it } }
            val profile = Profile(details)
            // The people the viewer likes most: their other titles join the candidates.
            val people = profile.topPeople(3).map { (person, w) -> async { limited { tmdb.credits(series, person) } to w } }
            val genres = profile.topGenres(2)
            val genreLists = genres.map { g -> async { limited { tmdb.list("discover/$type", 3, "with_genres" to "$g", "sort_by" to "popularity.desc", "vote_count.gte" to "150") } } }
            val gems = if (genres.isEmpty()) null else async {
                limited { tmdb.list("discover/$type", 3, "with_genres" to genres.joinToString("|"), "sort_by" to "vote_average.desc", "vote_count.gte" to "200", "vote_count.lte" to "4000") }
            }

            // Candidates: recommendations and similar titles of what was liked, the liked people's titles, the favourite genres.
            val score = HashMap<String, Double>()
            val items = HashMap<String, Tmdb.Item>()
            fun add(list: List<Tmdb.Item>, weight: Double) = list.forEachIndexed { pos, it ->
                if (it.id in profile.seenIds) return@forEachIndexed
                items.putIfAbsent(it.id, it)
                score[it.id] = (score[it.id] ?: 0.0) + weight * (1.0 - pos / 50.0)
            }
            details.forEach { (s, d) -> if (s.weight > 0) { add(d.recommendations, s.weight); add(d.similar, s.weight * 0.6) } }
            people.awaitAll().forEach { (list, w) -> add(list.take(30), w * 0.5) }
            val genreItems = genreLists.awaitAll()
            genreItems.forEach { add(it, 0.25) }
            val trendingList = trending.await()
            if (details.none { it.first.weight > 0 }) add(trendingList, 0.3) // nothing known of the viewer yet: what everyone watches
            val ranked = score.entries.mapNotNull { (id, base) ->
                val it = items.getValue(id)
                val fit = profile.genreFit(it.genres)
                if (fit < -0.6) null // mostly genres the viewer rates low
                else id to base + fit * 0.8 + kotlin.math.ln(1 + it.popularity) * 0.04 + (it.rating - 6.5) * 0.08
            }.sortedByDescending { it.second }.map { items.getValue(it.first) }
            val forYou = pick(ranked)
            val shown = forYou.take(ROW).map { it.id }.toSet()
            val because = profile.favourite()?.let { (s, d) ->
                Shelf((if (s.rated) "Parce que vous avez aimé " else "Parce que vous avez regardé ") + s.title, pick(d.recommendations + d.similar, shown))
            }
            val genreNames = genres.mapNotNull { Tmdb.GENRE_NAMES[it] }
            listOfNotNull(
                Shelf("Recommandé pour vous", forYou.take(ROW)),
                Shelf("Tendances du moment", pick(trendingList)),
                because,
                Shelf(if (series) "Nouveautés séries" else "Nouveautés cinéma", pick(fresh.await())),
                if (genreNames.isEmpty()) null else Shelf("Dans vos genres préférés · " + genreNames.joinToString(", "),
                    pick(genreItems.flatten().filter { profile.genreFit(it.genres) >= 0 }.sortedByDescending { it.popularity }, shown)),
                Shelf(if (series) "Séries populaires" else "Films populaires", pick(popular.await())),
                gems?.let { g -> Shelf("À découvrir", pick(g.await().filter { profile.genreFit(it.genres) >= 0 }, shown)) },
                Shelf(if (series) "Séries les mieux notées" else "Films les mieux notés", pick(top.await())),
                Shelf("Vous pourriez aussi aimer", forYou.drop(ROW)),
            )
        }
        emit(local + taste)

        // 3. Genres and streaming services
        val releaseKey = if (series) "first_air_date" else "primary_release_date"
        val extra = coroutineScope {
            val g = (if (series) Tmdb.TV_GENRES else Tmdb.MOVIE_GENRES).map { (name, id) ->
                async { Shelf(name, pick(limited { tmdb.list("discover/$type", 4, "with_genres" to "$id", "sort_by" to "popularity.desc", "vote_count.gte" to "50") })) }
            }
            // TMDB knows where a title can be watched in France, not since when. Films: the latest released ("sorties
            // récentes"). Series: those with an episode aired in the last 60 days, most popular first, so a new season of
            // an older series (Lupin) counts; daily shows (news, reality, soaps, talk shows) left out.
            val since = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 60 * 86_400_000L))
            val p = Tmdb.PLATFORMS.map { (name, id) ->
                async {
                    val where = arrayOf("with_watch_providers" to "$id", "watch_region" to "FR", "with_watch_monetization_types" to "flatrate", "vote_count.gte" to "10")
                    if (series) Shelf("Nouveaux épisodes sur $name", pick(limited {
                        tmdb.list("discover/tv", 5, *where, "air_date.gte" to since, "air_date.lte" to today, "sort_by" to "popularity.desc",
                            "without_genres" to "10763,10764,10766,10767")
                    }))
                    else Shelf("Sur $name · sorties récentes", pick(limited {
                        tmdb.list("discover/movie", 5, *where, "sort_by" to "$releaseKey.desc", "$releaseKey.lte" to today)
                    }))
                }
            }
            (g + p).awaitAll()
        }
        emit(local + taste + extra)
    }.flowOn(Dispatchers.IO)

    /** One copy per title. */
    private fun one(list: List<VodLite>) = list.distinctBy { it.id }.distinctBy { Featured.baseTitle(it.name) }.take(ROW * 2)

    /** What the viewer watched or rated: its TMDB id, and how much it says about the taste (−2 … +2). */
    private class Seed(val tmdbId: String, val title: String, val weight: Double, val rated: Boolean)

    private suspend fun seeds(series: Boolean, pid: Long, history: List<HistoryEntity>, ratings: List<RatingEntity>,
                              byUid: Map<String, VodLite>, seen: MutableSet<String>): List<Seed> {
        val rated = ratings.associateBy { it.uid }
        seen += rated.keys
        // uid → how much of it was watched (0..1), newest first; a series: by its episodes seen to the end.
        val watched = LinkedHashMap<String, Double>()
        if (series) history.filter { it.kind == HistoryKind.EPISODE && it.playlistId == pid && it.parentUid != null }
            .groupBy { it.parentUid!! }.forEach { (uid, eps) -> watched[uid] = (eps.count { Resume.watched(it.positionMs, it.durationMs) } / 3.0).coerceIn(0.3, 1.0) }
        else history.filter { it.kind == HistoryKind.MOVIE && it.playlistId == pid }
            .forEach { h -> watched[h.uid] = if (h.durationMs > 0) (h.positionMs.toDouble() / h.durationMs).coerceIn(0.0, 1.0) else 0.5 }
        seen += watched.keys
        val uids = (ratings.sortedByDescending { it.ratedAt }.map { it.uid } + watched.keys).distinct().take(MAX_SEEDS)
        return uids.mapIndexedNotNull { i, uid ->
            val v = byUid[uid] ?: return@mapIndexedNotNull null
            val r = rated[uid]
            // Rated: 5 → +2 … 1 → −2. Watched only: to the end → +1, started → less. Older ones count a little less.
            val w = (r?.let { (it.stars - 3).toDouble() } ?: (watched[uid] ?: 0.0).coerceAtLeast(0.2)) / (1 + i * 0.12)
            val id = link(series, pid, v) ?: return@mapIndexedNotNull null
            Seed(id, v.name.substringBefore(" (").substringBefore(" [").trim(), w, r != null)
        }
    }

    /** The TMDB id of a playlist title: the provider's when it gives one, else a search by title and year; kept in the database. */
    private suspend fun link(series: Boolean, pid: Long, v: VodLite): String? {
        val key = (if (series) "se" else "mv") + ":$pid:${v.uid}"
        db.ratings().link(key)?.let { if (it.tmdbId != null || System.currentTimeMillis() - it.checkedAt < 7 * 86_400_000L) return it.tmdbId }
        val provided = if (series) db.vod().seriesByUid(pid, v.uid)?.s?.tmdb else null
        val id = provided ?: limited { tmdb.idOf(series, v.name.substringBefore(" (").substringBefore(" [").trim(), Featured.yearOf(v.year, v.name)) }
        db.ratings().saveLink(TmdbLinkEntity(key, id, System.currentTimeMillis()))
        return id
    }

    /** The viewer's taste: weights of genres and people, from the seeds' details. */
    private class Profile(val seeds: List<Pair<Seed, Tmdb.Details>>) {
        val seenIds = seeds.map { it.second.id }.toSet()
        private val genres = HashMap<Int, Double>()
        private val people = HashMap<Int, Double>()
        init {
            seeds.forEach { (s, d) ->
                d.genres.forEach { genres[it] = (genres[it] ?: 0.0) + s.weight }
                d.cast.take(5).forEach { people[it] = (people[it] ?: 0.0) + s.weight * 0.6 }
                d.directors.forEach { people[it] = (people[it] ?: 0.0) + s.weight }
            }
        }
        private val scale = (genres.values.maxOfOrNull { kotlin.math.abs(it) } ?: 1.0).coerceAtLeast(1.0)

        /** −1 (genres the viewer rates low) … +1 (the favourite ones), 0 when unknown. */
        fun genreFit(ids: List<Int>): Double = if (ids.isEmpty()) 0.0 else ids.sumOf { (genres[it] ?: 0.0) / scale } / ids.size

        fun topGenres(n: Int) = genres.entries.filter { it.value > 0.5 }.sortedByDescending { it.value }.take(n).map { it.key }
        fun topPeople(n: Int) = people.entries.filter { it.value >= 1.2 }.sortedByDescending { it.value }.take(n).map { it.key to it.value }

        /** The best liked title: rated highest, else the most watched recently. */
        fun favourite() = seeds.filter { it.first.weight > 0 }.maxByOrNull { it.first.weight }
    }

    companion object {
        /** Titles per row (at most): enough to browse, rows stay quick (posters are loaded as they come into view). */
        const val ROW = 40
        const val MIN_ROW = 3
        /** Shown from the first rating. */
        const val MY_RATINGS = "Vos notes"
        const val MAX_SEEDS = 12
    }
}
