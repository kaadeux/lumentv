package com.lumentv.core

import java.util.Calendar

/**
 * The rows that put films and series forward above the full lists (same on the TV, the phone and the PC), for whoever
 * does not know what to watch: what is new, the best rated of the last years, and more of what was watched last.
 *
 * A provider lists the same film several times (4K, FHD, "SUBT AR"…): one poster per title is kept, the first in the
 * given order. Titles in another script or only subtitled in Arabic are left out, as are hidden categories.
 */
object Featured {
    const val ROW = 20
    /** "Les mieux notés": at least this rating, released in the last [RECENT_YEARS] years. */
    const val GOOD = 7.0
    const val RECENT_YEARS = 3
    /** Above this, a rating is usually a handful of votes (an unknown film "★ 10"), not a sure value. */
    const val BEST = 9.0

    /** "The Dog Stars (2026) 4K MULTI" and "The Dog Stars (2026) SUBT AR" are one title: "the dog stars". */
    fun baseTitle(name: String): String =
        normalize(name.substringBefore(" (").substringBefore(" [").substringBefore(" | ").replace(Regex("\\b(4K|FHD|HD|SD|MULTI|VOSTFR|VOST|VF|VFF|VOF|SUBT AR)\\b.*$", RegexOption.IGNORE_CASE), ""))

    /** Arabic titles, Arabic-subtitled copies and Arabic categories, not for this family. */
    fun foreign(name: String, category: String): Boolean =
        name.any { it in '؀'..'ۿ' } || Regex("\\bSUBT AR\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
            category.trimStart().startsWith("AR", ignoreCase = true)

    fun rating(r: String?): Double? = r?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
    fun year(y: String?): Int? = y?.take(4)?.toIntOrNull()

    /** The year given, else the one in the title: "Breach (2026) VOSTFR" → 2026. */
    fun yearOf(y: String?, name: String): Int? = year(y) ?: Regex("\\((\\d{4})\\)").find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** Released in the last [RECENT_YEARS] years. */
    fun recent(y: String?, name: String) = (yearOf(y, name) ?: 0) >= Calendar.getInstance().get(Calendar.YEAR) - RECENT_YEARS

    /** One per title, without the foreign copies and hidden categories, at most [ROW]. */
    fun <T> clean(list: List<T>, name: (T) -> String, category: (T) -> String, hidden: Set<String> = emptySet()): List<T> =
        list.filter { !foreign(name(it), category(it)) && category(it) !in hidden }.distinctBy { baseTitle(name(it)) }.take(ROW)
}
