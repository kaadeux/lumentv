package com.lumentv.core

/**
 * Titles of the playlist as the Films and Séries screens show them (same on the TV, the phone and the PC).
 *
 * A provider lists the same film several times (4K, FHD, "SUBT AR"…): one poster per title is kept, the first in the
 * given order. Titles in another script or only subtitled in Arabic are left out, as are hidden categories.
 */
object Featured {
    /** Titles of a category row. */
    const val ROW = 20

    /** "The Dog Stars (2026) 4K MULTI" and "The Dog Stars (2026) SUBT AR" are one title: "the dog stars". */
    fun baseTitle(name: String): String =
        normalize(name.substringBefore(" (").substringBefore(" [").substringBefore(" | ").replace(Regex("\\b(4K|FHD|HD|SD|MULTI|VOSTFR|VOST|VF|VFF|VOF|SUBT AR)\\b.*$", RegexOption.IGNORE_CASE), ""))

    /**
     * Arabic titles, Arabic-subtitled or dubbed copies and Arabic categories, not for this family: "AR · ARABE",
     * "Séries SUB-AR", "Séries DUB-AR", "Séries ARABES", "AR | The Greatest"…
     */
    fun foreign(name: String, category: String): Boolean =
        name.any { it in '؀'..'ۿ' } || subtAr.containsMatchIn(name) || arPrefix.containsMatchIn(name) ||
            category.trimStart().startsWith("AR", ignoreCase = true) || arCategory.containsMatchIn(category)

    private val subtAr = Regex("\\bSUBT AR\\b", RegexOption.IGNORE_CASE)
    private val arPrefix = Regex("^\\s*AR\\s*[|:·-]")
    private val arCategory = Regex("\\b(SUBT?|DUB)[\\s-]*AR\\b|\\bARAB(E|ES|Y|IC|IQUE|IQUES)?\\b", RegexOption.IGNORE_CASE)

    fun rating(r: String?): Double? = r?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
    fun year(y: String?): Int? = y?.take(4)?.toIntOrNull()

    /** The year given, else the one in the title: "Breach (2026) VOSTFR" → 2026. */
    fun yearOf(y: String?, name: String): Int? = year(y) ?: Regex("\\((\\d{4})\\)").find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** "Hope (2026) MULTI" → "Hope": the name without the year and the copy's tags (they go to [posterLine]). */
    fun shortTitle(name: String): String = name.substringBefore(" (").substringBefore(" [").trim().ifBlank { name }

    /** "Breach (2026) VOSTFR" → "VOSTFR", "The Dog Stars (2026) 4K MULTI" → "4K MULTI": what tells a copy from another. */
    fun versionLabel(name: String): String =
        name.substringAfterLast(")", "").replace(Regex("^[\\s\\-|:]+"), "").trim().ifBlank { name.substringAfter(" | ", "").trim() }.ifBlank { "Version" }

    /** "2026 · ★ 7,2 · MULTI": year, rating, and for a film ([name] given) which copy it is. */
    fun posterLine(year: String?, rating: String?, name: String?): String? =
        (listOfNotNull(yearOf(year, name ?: "")?.toString(), rating(rating)?.let { "★ " + "%.1f".format(java.util.Locale.FRANCE, it) }) +
            listOfNotNull(name?.let { versionLabel(it) }?.takeIf { it != "Version" && it.length <= 14 })).joinToString(" · ").ifEmpty { null }

    /** One per title, without the foreign copies and hidden categories, at most [ROW]. */
    fun <T> clean(list: List<T>, name: (T) -> String, category: (T) -> String, hidden: Set<String> = emptySet()): List<T> =
        list.filter { !foreign(name(it), category(it)) && category(it) !in hidden }.distinctBy { baseTitle(name(it)) }.take(ROW)
}
