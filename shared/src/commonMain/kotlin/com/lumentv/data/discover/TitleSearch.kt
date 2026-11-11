package com.lumentv.data.discover

import com.lumentv.core.Featured
import com.lumentv.core.SmartSearch
import com.lumentv.data.Tmdb
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The search of films and series, the same on the TV, the phone and the PC: a tolerant search in the playlist's
 * titles ([SmartSearch]) plus the titles TMDB finds under another name (French or original) that the playlist has,
 * and the people whose name is the query with what they played in or made.
 */
object TitleSearch {
    const val LIMIT = 60
    const val PERSON_LIMIT = 40
    /** How long the search waits for TMDB (other titles, actors) before showing the playlist's own results. */
    const val ONLINE_MS = 3_000L

    /** "Avec Tom Holland", "Réalisé par …" or "Créé par …": the playlist's titles of one person, in TMDB's order (most popular first). */
    class PersonHit(val key: String, val title: String, val movies: List<VodLite>, val series: List<VodLite>)

    class Results(val movies: List<VodLite>, val series: List<VodLite>, val people: List<PersonHit>)

    suspend fun search(query: String, idx: TitleIndex, tmdb: Tmdb, limit: Int = LIMIT): Results = coroutineScope {
        val sq = SmartSearch.Query(query)
        if (!sq.valid) return@coroutineScope Results(emptyList(), emptyList(), emptyList())
        val online = tmdb.enabled && sq.compact.length >= 3
        // TMDB at most 3 s: a slow server left the whole search on "Recherche…", the playlist's titles with it.
        val people = async { if (online) kotlinx.coroutines.withTimeoutOrNull(ONLINE_MS) { people(query, idx, tmdb) }.orEmpty() else emptyList() }
        // Other titles: TMDB's French and original titles of what the query means ("seigneur des anneaux" → "The Lord of the Rings").
        val otherMovies = async { if (online) kotlinx.coroutines.withTimeoutOrNull(ONLINE_MS) { runCatching { tmdb.search(false, query).take(8).mapNotNull { idx.find(false, it) } }.getOrDefault(emptyList()) }.orEmpty() else emptyList() }
        val otherSeries = async { if (online) kotlinx.coroutines.withTimeoutOrNull(ONLINE_MS) { runCatching { tmdb.search(true, query).take(8).mapNotNull { idx.find(true, it) } }.getOrDefault(emptyList()) }.orEmpty() else emptyList() }
        fun merge(local: List<VodLite>, alt: List<VodLite>) = sameTitleOnce((local + alt).distinctBy { it.id }).take(limit)
        Results(
            merge(SmartSearch.search(sq, idx.movies, limit * 2), otherMovies.await()),
            merge(SmartSearch.search(sq, idx.series, limit * 2), otherSeries.await()),
            people.await(),
        )
    }

    /**
     * One copy per title: the first (best) one. Two titles of the same name years apart stay both ("One Piece" 1999,
     * the anime, and 2023, Netflix's); a copy without a year counts as the same title.
     */
    fun sameTitleOnce(list: List<VodLite>): List<VodLite> {
        val kept = HashMap<String, MutableList<Int?>>()
        return list.filter { v ->
            val years = kept.getOrPut(Featured.baseTitle(v.name)) { ArrayList() }
            val y = Featured.yearOf(v.year, v.name)
            if (years.any { it == null || y == null || kotlin.math.abs(it - y) <= 1 }) false else { years += y; true }
        }
    }

    /** The two best known people whose name is the query, and what they played in / made that the playlist has. */
    private suspend fun people(query: String, idx: TitleIndex, tmdb: Tmdb): List<PersonHit> = coroutineScope {
        runCatching { tmdb.people(query) }.getOrDefault(emptyList()).take(2).map { person ->
            async {
                val works = runCatching { tmdb.works(person.id) }.getOrDefault(emptyList())
                listOf(true, false).mapNotNull { made ->
                    val found = works.filter { it.made == made }.mapNotNull { w -> idx.find(w.series, w.item)?.let { w.series to it } }
                        .filter { (_, v) -> !Featured.foreign(v.name, v.category) }
                        .distinctBy { (series, v) -> "$series${Featured.baseTitle(v.name)}" }.take(PERSON_LIMIT)
                    if (found.isEmpty()) return@mapNotNull null
                    val movies = found.filter { !it.first }.map { it.second }
                    val series = found.filter { it.first }.map { it.second }
                    val title = when {
                        !made -> "Avec ${person.name}"
                        movies.isEmpty() -> "Créé par ${person.name}"
                        else -> "Réalisé par ${person.name}"
                    }
                    PersonHit("${person.id}:$made", title, movies, series)
                }
            }
        }.flatMap { it.await() }
    }
}
