package com.lumentv.data

import com.lumentv.core.normalize

/**
 * TMDB answers a search with whatever comes close: its first result can be another film. A result is kept only when
 * its title (French or original) is the one searched, or shares most of its words ("Harry Potter 1" and
 * "Harry Potter à l'école des sorciers"); else no result, and the page keeps the provider's summary.
 */
object TmdbMatch {
    private const val MIN_SHARED_WORDS = 0.6

    /** How well [names] answer [query]: 3 the same title, 2 inside the result's, 1 most words shared, 0 another title. */
    fun score(query: String, names: List<String>): Int {
        val q = normalize(query)
        if (q.isEmpty()) return 0
        val words = q.split(' ').toSet()
        return names.maxOfOrNull { name ->
            val n = normalize(name)
            when {
                n.isEmpty() -> 0
                n == q -> 3
                // The title searched inside the result's ("Dune" and "Dune : Deuxième partie"). Not the other way
                // round: a film called "Vivre" is not "Un an à vivre, un homme à acheter".
                " $n ".contains(" $q ") -> 2
                words.count { it in n.split(' ') } >= words.size * MIN_SHARED_WORDS -> 1
                else -> 0
            }
        } ?: 0
    }

    /** The best answer to [query], the first at the same level (TMDB sorts by relevance); null when none is that title. */
    fun <T> best(query: String, results: List<T>, names: (T) -> List<String>): T? {
        var best: T? = null
        var top = 0
        for (r in results) {
            val s = score(query, names(r))
            if (s > top) { best = r; top = s }
        }
        return best
    }

    /**
     * Searches with the year, then without it when nothing there is that title: a provider's year is often off by
     * one or wrong. [search] asks TMDB for the title with this year (null: no year).
     */
    fun <T> find(query: String, year: Int?, search: (Int?) -> List<T>, names: (T) -> List<String>): T? {
        if (year != null) best(query, search(year), names)?.let { return it }
        return best(query, search(null), names)
    }
}
