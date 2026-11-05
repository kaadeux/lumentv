package com.lumentv.data.discover

import com.lumentv.core.Featured
import com.lumentv.data.Tmdb
import com.lumentv.core.IoDispatcher
import kotlinx.coroutines.async
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A row of the Films / Séries home. */
class Row(val title: String, val items: List<VodLite>)

/** A personal rating (1 to 5 stars) of a playlist title. */
class Rated(val uid: String, val stars: Int, val ratedAt: Long)

/** What a home is made from, on one playlist. */
class DiscoverInput(
    val series: Boolean,
    val index: TitleIndex,
    /** The newest titles of the playlist, newest first ("Ajoutés récemment"). */
    val recent: List<VodLite>,
    val rated: List<Rated>,
    /** uid → how much of it was watched (0..1), the last watched first. A series: by its episodes. */
    val watched: LinkedHashMap<String, Double>,
    /** Categories the viewer hid. */
    val hidden: Set<String> = emptySet(),
    /** The TMDB id of a playlist title (the provider's, or found by title and year), cached by the caller. */
    val link: suspend (VodLite) -> String?,
)

/**
 * The rows of the Films and Séries homes, the same on the TV, the phone and the PC. Every row only keeps titles the
 * playlist really has (found through [TitleIndex]); TMDB only says what to put forward. Three steps, each emitted as
 * soon as it is ready:
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
class DiscoverEngine(private val tmdb: Tmdb) {
    private val gate = Semaphore(6)
    private suspend fun <T> limited(block: () -> T): T = gate.withPermit { block() }

    /** The rows, unfiltered: the caller keeps those with at least [MIN_ROW] titles it can show ([keep]). */
    fun rows(input: DiscoverInput): Flow<List<Row>> = flow {
        val series = input.series
        val idx = input.index
        val byUid = (if (series) idx.series else idx.movies).associateBy({ it.item.uid }, { it.item })
        fun ok(v: VodLite) = v.category !in input.hidden && !Featured.foreign(v.name, v.category)

        // 1. The playlist alone
        val local = listOf(
            Row(if (series) "Ajoutées récemment" else "Ajoutés récemment", one(input.recent.filter(::ok))),
            Row(MY_RATINGS, input.rated.sortedWith(compareByDescending<Rated> { it.stars }.thenByDescending { it.ratedAt }).mapNotNull { byUid[it.uid] }),
        )
        emit(local)
        if (!tmdb.enabled) return@flow

        // 2. TMDB and the taste
        val seen = HashSet<String>() // uids watched or rated: never proposed
        val seeds = seeds(input, byUid, seen)
        val type = if (series) "tv" else "movie"
        val region = if (series) emptyArray() else arrayOf("region" to "FR")
        val today = isoDay(com.lumentv.core.nowMillis())
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
                Row((if (s.rated) "Parce que vous avez aimé " else "Parce que vous avez regardé ") + s.title, pick(d.recommendations + d.similar, shown))
            }
            val genreNames = genres.mapNotNull { Tmdb.GENRE_NAMES[it] }
            listOfNotNull(
                Row("Recommandé pour vous", forYou.take(ROW)),
                Row("Tendances du moment", pick(trendingList)),
                because,
                Row(if (series) "Nouveautés séries" else "Nouveautés cinéma", pick(fresh.await())),
                if (genreNames.isEmpty()) null else Row("Dans vos genres préférés · " + genreNames.joinToString(", "),
                    pick(genreItems.flatten().filter { profile.genreFit(it.genres) >= 0 }.sortedByDescending { it.popularity }, shown)),
                Row(if (series) "Séries populaires" else "Films populaires", pick(popular.await())),
                gems?.let { g -> Row("À découvrir", pick(g.await().filter { profile.genreFit(it.genres) >= 0 }, shown)) },
                Row(if (series) "Séries les mieux notées" else "Films les mieux notés", pick(top.await())),
                Row("Vous pourriez aussi aimer", forYou.drop(ROW)),
            )
        }
        emit(local + taste)

        // 3. Genres and streaming services
        val releaseKey = if (series) "first_air_date" else "primary_release_date"
        val extra = coroutineScope {
            val g = (if (series) Tmdb.TV_GENRES else Tmdb.MOVIE_GENRES).map { (name, id) ->
                async { Row(name, pick(limited { tmdb.list("discover/$type", 4, "with_genres" to "$id", "sort_by" to "popularity.desc", "vote_count.gte" to "50") })) }
            }
            // TMDB knows where a title can be watched in France, not since when. Films: the latest released ("sorties
            // récentes"). Series: those with an episode aired in the last 60 days, most popular first, so a new season of
            // an older series (Lupin) counts; daily shows (news, reality, soaps, talk shows) left out.
            val since = isoDay(com.lumentv.core.nowMillis() - 60 * 86_400_000L)
            val p = Tmdb.PLATFORMS.map { (name, id) ->
                async {
                    val where = arrayOf("with_watch_providers" to "$id", "watch_region" to "FR", "with_watch_monetization_types" to "flatrate", "vote_count.gte" to "10")
                    if (series) Row("Nouveaux épisodes sur $name", pick(limited {
                        tmdb.list("discover/tv", 5, *where, "air_date.gte" to since, "air_date.lte" to today, "sort_by" to "popularity.desc",
                            "without_genres" to "10763,10764,10766,10767")
                    }))
                    else Row("Sur $name · sorties récentes", pick(limited {
                        tmdb.list("discover/movie", 5, *where, "sort_by" to "$releaseKey.desc", "$releaseKey.lte" to today)
                    }))
                }
            }
            (g + p).awaitAll()
        }
        emit(local + taste + extra)
    }.flowOn(IoDispatcher)

    /** One copy per title. */
    private fun one(list: List<VodLite>) = list.distinctBy { it.id }.distinctBy { Featured.baseTitle(it.name) }.take(ROW * 2)

    /** What the viewer watched or rated: its TMDB id, and how much it says about the taste (−2 … +2). */
    private class Seed(val tmdbId: String, val title: String, val weight: Double, val rated: Boolean)

    private suspend fun seeds(input: DiscoverInput, byUid: Map<String, VodLite>, seen: MutableSet<String>): List<Seed> {
        val rated = input.rated.associateBy { it.uid }
        seen += rated.keys
        seen += input.watched.keys
        val uids = (input.rated.sortedByDescending { it.ratedAt }.map { it.uid } + input.watched.keys).distinct().take(MAX_SEEDS)
        return uids.mapIndexedNotNull { i, uid ->
            val v = byUid[uid] ?: return@mapIndexedNotNull null
            val r = rated[uid]
            // Rated: 5 → +2 … 1 → −2. Watched only: to the end → +1, started → less. Older ones count a little less.
            val w = (r?.let { (it.stars - 3).toDouble() } ?: (input.watched[uid] ?: 0.0).coerceAtLeast(0.2)) / (1 + i * 0.12)
            val id = input.link(v) ?: return@mapIndexedNotNull null
            Seed(id, Featured.shortTitle(v.name), w, r != null)
        }
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

        /** Whether a row with [shown] titles goes on the home. */
        fun keep(title: String, shown: Int) = shown >= MIN_ROW || (title == MY_RATINGS && shown > 0)
    }
}

/** "2026-11-05": the day (on this device's clock) of an instant, as TMDB's date filters want it. */
internal fun isoDay(ms: Long): String =
    kotlinx.datetime.Instant.fromEpochMilliseconds(ms).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date.toString()
