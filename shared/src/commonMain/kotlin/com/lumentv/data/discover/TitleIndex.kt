package com.lumentv.data.discover

import com.lumentv.core.Featured
import com.lumentv.core.SmartSearch
import com.lumentv.data.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A film or series of a playlist, light (names only). [id] is unique within its kind on that playlist. */
data class VodLite(val id: Long, val uid: String, val name: String, val category: String, val year: String?, val rating: String?)

/**
 * A playlist's films and series in memory: prepared titles for the search, and the playlist's copy of a TMDB title
 * (by its French or original title, ignoring spaces and dashes, year ± 1). Copies marked for another language,
 * ([Featured.foreign]) are left out. Same on the TV, the phone and the PC.
 */
open class TitleIndex(prepared: Prepared) {
    /** The titles kept and prepared ([prepare]); made apart so that a big list can be prepared on every core at once. */
    class Prepared(val movies: List<SmartSearch.Entry<VodLite>>, val series: List<SmartSearch.Entry<VodLite>>)

    constructor(movies: List<VodLite>, series: List<VodLite>) : this(Prepared(prepare(movies), prepare(series)))

    val movies: List<SmartSearch.Entry<VodLite>> = prepared.movies
    val series: List<SmartSearch.Entry<VodLite>> = prepared.series
    // From the prepared titles: each title cleaned once, not twice (the time of a big list on a TV).
    private val moviesByTitle = this.movies.groupBy({ it.compact }, { it.item })
    private val seriesByTitle = this.series.groupBy({ it.compact }, { it.item })

    /** The playlist's copy of a TMDB title (in French when there is one, then subtitled, then other languages; the best quality first), or null. */
    fun find(series: Boolean, title: String, original: String, year: Int?, english: String = ""): VodLite? {
        val map = if (series) seriesByTitle else moviesByTitle
        val candidates = (map[key(title)].orEmpty() + map[key(original)].orEmpty() +
            (if (english.isBlank()) emptyList() else map[key(english)].orEmpty())).distinctBy { it.id }
        if (candidates.isEmpty()) return null
        val sameYear = candidates.filter { c -> val y = Featured.yearOf(c.year, c.name); y == null || year == null || kotlin.math.abs(y - year) <= 1 }
        return sameYear.minWithOrNull(compareBy({ SmartSearch.languageRank(it.name) }, { SmartSearch.qualityRank(it.name) }))
    }

    /** Also by its English title, when known: lists often name foreign titles in English ("A Couple of Lies" = "Bytet"). */
    fun find(series: Boolean, item: Tmdb.Item) = find(series, item.title, item.original, item.year, item.english)

    companion object {
        // Copies in another language ("DE - …", "(ITA)") are left out too: never shown in the rows, and on a list of every
        // country they were most of the titles, too many for a TV's memory.
        // "(US)" or "(EN)" after the title only puts the copy after the French ones: "EN - FROM (2022) (US)" is the series.
        private fun keep(v: VodLite) = !Featured.foreign(v.name, v.category) && !Featured.otherLanguage(v.name)

        /** The titles kept, each cleaned for the search and the rows. */
        fun prepare(list: List<VodLite>): List<SmartSearch.Entry<VodLite>> =
            list.mapNotNull { v -> if (keep(v)) SmartSearch.Entry(v, v.name) else null }

        /**
         * The same on every core: 234 000 titles took 11 s on a TV's single core, the Films rows waiting meanwhile.
         * The order of [list] is kept.
         */
        suspend fun prepareParallel(list: List<VodLite>): List<SmartSearch.Entry<VodLite>> = coroutineScope {
            if (list.size < 4_000) return@coroutineScope prepare(list)
            list.chunked(list.size / 8 + 1).map { part -> async(Dispatchers.Default) { prepare(part) } }.awaitAll().flatten()
        }

        /** "Spider-Man : No Way Home (2021) 4K" and "Spider-Man: No Way Home" → "spidermannowayhome". */
        fun key(title: String) = Featured.baseTitle(title).replace(" ", "")
    }
}
