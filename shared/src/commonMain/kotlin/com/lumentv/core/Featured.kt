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

    /**
     * The language some providers put before every title: "FR - Heart of the Beast (2026)", "IN-EN - The Highest Stakes",
     * "AR | The Greatest". Two letters, then a dash between spaces or a bar: "LOL - Qui rit sort" keeps its name.
     */
    private val langTag = Regex("^\\s*(?:[48]K[-/])?([A-Z]{2})(?:[-/][A-Z]{2,4}){0,2}\\s*(?:-\\s+|\\|\\s*)")

    /**
     * The tags of a copy written for another language than French or English ("DE - …", "IT|…"). A list, not "every
     * tag but FR and EN": "NF - From Scratch" is Netflix's series, not a language, and was left out of the search.
     */
    val foreignTags: Set<String> = setOf("DE", "NL", "GR", "SC", "SE", "NO", "DK", "FI", "IS", "AL", "IR", "AR", "ES", "IT", "PT", "BR", "TR", "PL", "RU", "IN", "RO", "HU", "CZ", "SK", "BG", "HR", "RS", "SI", "UA", "IL", "KR", "JP", "CN", "TH", "VN", "PH", "ID", "MY", "PK", "BD", "AF", "KU", "LA", "MX", "EX", "BS", "MK", "LT", "LV", "EE", "GE", "AM", "AZ", "KZ", "UZ", "TW", "HK")

    /** A copy written for another language ("DE - Dune"): out of the search and the rows. "EN - FROM (US)" stays. */
    fun otherLanguage(name: String): Boolean = langTag(name) in foreignTags

    /** "FR - Heart of the Beast (2026)" → "FR"; null without such a tag. */
    fun langTag(name: String): String? = langTag.find(name)?.groupValues?.get(1)?.uppercase()

    private val frenchWord = Regex("\\b(FR|FRA|FRANCE|FRENCH|FRANCAIS|FRANÇAIS|VF|VFF)\\b", RegexOption.IGNORE_CASE)
    private val francophone = Regex("\\b(BELGIQUE|BELGIUM|SUISSE|SWITZERLAND|ROMANDE?|ROMANDIE|QU[EÉ]BEC|WALLONIE|LUXEMBOURG|MONACO)\\b", RegexOption.IGNORE_CASE)

    /** The country or language a group starts with, however the provider writes it: "FR|", "FR -", "FR:", "[FR]", "|FR|", "FR ·". */
    private val leadCode = Regex("^[^A-Za-z0-9]*([A-Z]{2})(?![A-Za-z0-9])")
    private val francophoneCodes = setOf("BE", "CH", "QC", "LU", "MC")

    /**
     * A group of channels, whatever the playlist: 0 French ("FR| CANAL+ LIVE", "[FR] Sport", "🇫🇷 Cinéma", "FRANCE HD",
     * "SPORT FR"), 1 French-speaking from elsewhere ("CA| GENERAL FR", "BE| …", "Suisse romande", "Québec"), 2 the others.
     */
    fun frenchRank(group: String): Int {
        val code = leadCode.find(group)?.groupValues?.get(1)
        val word = frenchWord.containsMatchIn(group)
        return when {
            "🇫🇷" in group || code == "FR" || (code == null && word) -> 0
            word || code in francophoneCodes || francophone.containsMatchIn(group) -> 1
            else -> 2
        }
    }

    /**
     * The group the TV channels open on: the first French one the viewer can open ("FR| PRIME"), not all the channels,
     * which on a list of every country took long to show. Null: no French group, all the channels then.
     */
    fun <T> startGroup(groups: List<T>, name: (T) -> String, usable: (T) -> Boolean = { true }): String? =
        groups.firstOrNull { frenchRank(name(it)) == 0 && usable(it) }?.let(name)

    /** The groups in French first, each part in the provider's order: the same on every app. */
    fun <T> frenchFirst(list: List<T>, name: (T) -> String): List<T> = list.sortedBy { frenchRank(name(it)) }

    /**
     * An episode's line as the history keeps it, readable: "S1 E8 · FR - Off Campus (2026) (US) - S01E08 - Le Changement
     * de ligne" → "S1 E8 · Le Changement de ligne" (the provider repeats the series and the number in the title).
     */
    fun episodeLine(line: String?): String? {
        if (line.isNullOrBlank()) return line
        val head = line.substringBefore(" · ", "")
        val rest = if (head.isEmpty()) line else line.substringAfter(" · ")
        val name = episodeNumber.find(rest)?.let { rest.substring(it.range.last + 1).trim(' ', '-', '|', ':', '·') }?.takeIf { it.isNotBlank() }
            ?: untagged(rest)
        return if (head.isEmpty()) name else "$head · $name"
    }

    private val episodeNumber = Regex("\\bS\\d{1,2}\\s*E\\d{1,3}\\b", RegexOption.IGNORE_CASE)

    /** "FR - Heart of the Beast (2026)" → "Heart of the Beast (2026)". */
    fun untagged(name: String): String {
        // The copy's tags too ("TOP - ", "4K-TOP - ", "SE-CAM - "): the same film, not another one in the search.
        copyPrefix.find(name)?.let { m ->
            if (m.groupValues[1].split('-', '/').any { it in tagNames || it == "CAM" }) return name.substring(m.range.last + 1).ifBlank { name }
        }
        return name.replaceFirst(langTag, "").ifBlank { name }
    }

    /** "The Dog Stars (2026) 4K MULTI", "The Dog Stars (2026) SUBT AR" and "FR - The Dog Stars" are one title: "the dog stars". */
    fun baseTitle(name: String): String =
        normalize(untagged(name).substringBefore(" (").substringBefore(" [").substringBefore(" | ").replace(baseTags, ""))

    // Made once: built at each call, it took most of the time of indexing a big list on a TV.
    private val baseTags = Regex("\\b(4K|FHD|HD|SD|MULTI|VOSTFR|VOST|VF|VFF|VOF|SUBT AR)\\b.*$", RegexOption.IGNORE_CASE)

    /**
     * Arabic titles, Arabic-subtitled or dubbed copies and Arabic categories, not for this family: "AR · ARABE",
     * "Séries SUB-AR", "Séries DUB-AR", "Séries ARABES", "AR | The Greatest"…
     */
    fun foreign(name: String, category: String): Boolean =
        name.any { it in '؀'..'ۿ' } || subtAr.containsMatchIn(name) || arPrefix.containsMatchIn(name) || langTag(name) == "AR" ||
            category.trimStart().startsWith("AR", ignoreCase = true) || arCategory.containsMatchIn(category)

    private val subtAr = Regex("\\bSUBT AR\\b", RegexOption.IGNORE_CASE)
    private val arPrefix = Regex("^\\s*AR\\s*[|:·-]")
    private val arCategory = Regex("\\b(SUBT?|DUB)[\\s-]*AR\\b|\\bARAB(E|ES|Y|IC|IQUE|IQUES)?\\b", RegexOption.IGNORE_CASE)

    fun rating(r: String?): Double? = r?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
    fun year(y: String?): Int? = y?.take(4)?.toIntOrNull()

    /** The year given, else the one in the title: "Breach (2026) VOSTFR" → 2026. */
    fun yearOf(y: String?, name: String): Int? = year(y) ?: yearInName.find(name)?.let { (it.groups[1] ?: it.groups[2])?.value?.toIntOrNull() }

    /** "(2012)", or " - 2012" as some providers write it ("Les Misérables - 2012"). */
    private val yearInName = Regex("\\(((?:19|20)\\d{2})\\)|\\s-\\s((?:19|20)\\d{2})\\b")

    /**
     * The title as TMDB knows it: without the year and the copy's tags. "Les Misérables SD", "Les Misérables - 2012",
     * "Dune (2021) 4K MULTI" → "Les Misérables", "Les Misérables", "Dune".
     */
    fun searchTitle(name: String): String =
        shortTitle(name).replace(yearSuffix, "").replace(copyTags, "").trim(' ', '-', '|', ':').ifBlank { shortTitle(name) }

    private val yearSuffix = Regex("\\s-\\s(19|20)\\d{2}\\b.*$")
    private val copyTags = Regex("\\b(4K|UHD|FHD|HD|SD|HDR|MULTI|VOSTFR|VOST|VFF|VFQ|VF|VOF|VO)\\b", RegexOption.IGNORE_CASE)

    /** "Hope (2026) MULTI" → "Hope": the name without the year and the copy's tags (they go to [posterLine]). */
    fun shortTitle(name: String): String = untagged(name).substringBefore(" (").substringBefore(" [").trim().ifBlank { name }

    /** The tags a provider puts before a copy's title: "4K-SC-HDR - ", "AR-SUBS - ", "FR | ". */
    private val copyPrefix = Regex("^\\s*([A-Z0-9]{2,4}(?:[-/][A-Z0-9]{2,4}){0,3})\\s*(?:-\\s+|\\|\\s*)")
    private val tagNames = mapOf(
        "FR" to "Français", "EN" to "Anglais", "DE" to "Allemand", "NL" to "Néerlandais", "GR" to "Grec", "SC" to "Scandinave",
        "SE" to "Suédois", "NO" to "Norvégien", "DK" to "Danois", "FI" to "Finnois", "IS" to "Islandais", "AL" to "Albanais",
        "IR" to "Persan", "AR" to "Arabe", "ES" to "Espagnol", "IT" to "Italien", "PT" to "Portugais", "BR" to "Brésilien",
        "TR" to "Turc", "PL" to "Polonais", "RU" to "Russe", "IN" to "Indien", "RO" to "Roumain", "HU" to "Hongrois",
        "4K" to "4K", "8K" to "8K", "HDR" to "HDR", "TOP" to "Top", "SUBS" to "sous-titré", "SUB" to "sous-titré", "MULTI" to "MULTI",
    )

    /**
     * What tells a copy from another: "Breach (2026) VOSTFR" → "VOSTFR", "The Dog Stars (2026) 4K MULTI" → "4K MULTI",
     * "FR - Heart of the Beast (2026)" → "Français", "4K-SC-HDR - Heart of the Beast (2026)" → "4K · Scandinave · HDR".
     */
    fun versionLabel(name: String): String {
        val tags = copyPrefix.find(name)?.groupValues?.get(1)?.split('-', '/')?.takeIf { t -> t.any { it in tagNames } }.orEmpty()
        // After the year: in Latin letters only ("قلب هیولا" is the same title in Persian).
        // Letter by letter, not with a Unicode regex: the iPhone's regex engine is not Android's.
        val after = name.substringAfterLast(")", "")
            .map { c -> if ((c.isLetterOrDigit() && c.code !in 0x0590..0x08FF) || c == '+' || c == '-') c else ' ' }
            .joinToString("").trimStart(' ', '-').split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        val label = (tags.map { tagNames[it] ?: it } + listOfNotNull(after.takeIf { it.isNotEmpty() })).joinToString(" · ")
        return label.ifBlank { name.substringAfter(" | ", "").trim() }.ifBlank { "Version" }
    }

    /** "2026 · ★ 7,2 · MULTI": year, rating, and for a film ([name] given) which copy it is. */
    fun posterLine(year: String?, rating: String?, name: String?): String? =
        (listOfNotNull(yearOf(year, name ?: "")?.toString(), rating(rating)?.let { "★ " + oneDecimal(it) }) +
            listOfNotNull(name?.let { versionLabel(it) }?.takeIf { it != "Version" && it.length <= 14 })).joinToString(" · ").ifEmpty { null }

    /** One per title, without the foreign copies and hidden categories, at most [ROW]. */
    fun <T> clean(list: List<T>, name: (T) -> String, category: (T) -> String, hidden: Set<String> = emptySet()): List<T> =
        list.filter { !foreign(name(it), category(it)) && category(it) !in hidden }.distinctBy { baseTitle(name(it)) }.take(ROW)
}
