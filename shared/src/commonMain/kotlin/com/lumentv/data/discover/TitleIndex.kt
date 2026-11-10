package com.lumentv.data.discover

import com.lumentv.core.Featured
import com.lumentv.core.SmartSearch
import com.lumentv.data.Tmdb

/** A film or series of a playlist, light (names only). [id] is unique within its kind on that playlist. */
data class VodLite(val id: Long, val uid: String, val name: String, val category: String, val year: String?, val rating: String?)

/**
 * A playlist's films and series in memory: prepared titles for the search, and the playlist's copy of a TMDB title
 * (by its French or original title, ignoring spaces and dashes, year ± 1). Copies marked for another language,
 * ([Featured.foreign]) are left out. Same on the TV, the phone and the PC.
 */
open class TitleIndex(movies: List<VodLite>, series: List<VodLite>) {
    // Copies in another language ("DE - …", "(ITA)") are left out too: never shown in the rows, and on a list of every
    // country they were most of the titles, too many for a TV's memory.
    private val movieList = movies.filter { keep(it) }
    private val seriesList = series.filter { keep(it) }
    private fun keep(v: VodLite) = !Featured.foreign(v.name, v.category) && SmartSearch.languageRank(v.name) < 2
    val movies: List<SmartSearch.Entry<VodLite>> = movieList.map { SmartSearch.Entry(it, it.name) }
    val series: List<SmartSearch.Entry<VodLite>> = seriesList.map { SmartSearch.Entry(it, it.name) }
    private val moviesByTitle = movieList.groupBy { key(it.name) }
    private val seriesByTitle = seriesList.groupBy { key(it.name) }

    /** The playlist's copy of a TMDB title (in French when there is one, then subtitled, then other languages; the best quality first), or null. */
    fun find(series: Boolean, title: String, original: String, year: Int?): VodLite? {
        val map = if (series) seriesByTitle else moviesByTitle
        val candidates = (map[key(title)].orEmpty() + map[key(original)].orEmpty()).distinctBy { it.id }
        if (candidates.isEmpty()) return null
        val sameYear = candidates.filter { c -> val y = Featured.yearOf(c.year, c.name); y == null || year == null || kotlin.math.abs(y - year) <= 1 }
        return sameYear.minWithOrNull(compareBy({ SmartSearch.languageRank(it.name) }, { SmartSearch.qualityRank(it.name) }))
    }

    fun find(series: Boolean, item: Tmdb.Item) = find(series, item.title, item.original, item.year)

    companion object {
        /** "Spider-Man : No Way Home (2021) 4K" and "Spider-Man: No Way Home" → "spidermannowayhome". */
        fun key(title: String) = Featured.baseTitle(title).replace(" ", "")
    }
}
