package com.lumentv.core

/**
 * Tolerant title search, in levels: exact, then the start of the title, then a word, then anywhere, then every word of
 * the query in any order, then close spellings (typos). Accents, case, dashes, apostrophes and spaces do not count:
 * "spiderman" finds "Spider-Man", "lecole" finds "L'École". Quality and language marks of the provider ("4K", "VF",
 * "(2024)") are not part of the title. A shorter title comes before a longer one at the same level.
 */
object SmartSearch {
    /** A title prepared once (the index keeps these): its words and its letters without spaces. */
    /**
     * [otherLanguage]: marked for another language than French ("(IT)", "[ES]", "SUBT AR", "ENG"…), goes after the
     * other copies. [english]: only marked English ("(US)", "(EN)", "EN - …"), understood here, so just after the French copies.
     * Made from the title, or given as stored (the TV keeps its titles prepared: 234 000 took 11 s at each start).
     */
    class Entry<T>(val item: T, val norm: String, val otherLanguage: Boolean, val english: Boolean) {
        // "FR - One Piece (2023) (US)" is French: "(US)" after the title is where the series comes from. Taken for
        // English, it tied with Netflix's English copy "NF - ONE PIECE (2023) (US)", which came first.
        constructor(item: T, title: String) : this(item, Featured.baseTitle(title).ifBlank { normalize(title) },
            Featured.langTag(title) != "FR" && otherLang.containsMatchIn(title),
            // "EN - One Piece" too: tied with "FR - One Piece (1999) (JP)", the English copy came first and hid the French one.
            Featured.langTag(title) != "FR" && (Featured.langTag(title) == "EN" ||
                otherLang.containsMatchIn(title) && !otherLang.containsMatchIn(title.replace(englishMark, ""))))
        val compact: String = norm.replace(" ", "")
        val words: List<String> = norm.split(' ').filter { it.isNotEmpty() }
        private val wordCompacts = words.map { it }
        internal fun firstLetters(): Set<Char> = (wordCompacts.mapNotNull { it.firstOrNull() } + listOfNotNull(compact.firstOrNull())).toSet()
    }

    /** A query prepared once per search. */
    class Query(text: String) {
        val norm: String = normalize(text)
        val compact: String = norm.replace(" ", "")
        val words: List<String> = norm.split(' ').filter { it.isNotEmpty() }
        val typos: Int = when { compact.length < 4 -> 0; compact.length < 7 -> 1; else -> 2 }
        val valid get() = compact.length >= 2
    }

    private val otherLang = Regex("[(\\[](IT|ITA|ES|ESP|DE|GER|PT|BR|NL|PL|TR|RU|EN|ENG|US|UK|AR)[)\\]]|\\bSUBT\\s+AR\\b|\\b(ITA|ESP)\\b", RegexOption.IGNORE_CASE)

    private val vost = Regex("\\bVOST(FR)?\\b", RegexOption.IGNORE_CASE)

    /**
     * For choosing a copy of a title: marked French first (0, "FR - …", "4K-FR-HDR - …", "FR - … (US)": made in the US),
     * then copies that say no language ("TOP - …", "NF - …", "4K-AMZ - …", often the original version) or say
     * French in another way (1), subtitled (2), English and other languages (3). Unmarked copies came level with the
     * French ones, and first in the list, put forward 1 190 English films out of 2 733 a list had in French too.
     */
    fun languageRank(name: String): Int = when (val tag = Featured.langTag(name)) {
        "FR" -> if (vost.containsMatchIn(name)) 2 else 0
        "EN" -> 3
        null -> untaggedRank(name)
        else -> if (tag in Featured.foreignTags) 3 else untaggedRank(name)
    }

    /** No language tag in front: French by a word ("MULTI", "VF", "TRUEFRENCH": 0), else as marked after the title. */
    private fun untaggedRank(name: String): Int = when {
        frenchMark.containsMatchIn(name) -> 0
        vost.containsMatchIn(name) -> 2
        otherLang.containsMatchIn(name) -> 3
        else -> 1
    }

    private val frenchMark = Regex("\\b(MULTI|VFF?|VFQ|VOF|FRENCH|TRUEFRENCH)\\b", RegexOption.IGNORE_CASE)

    /** Among copies in the same language: FHD (0), HD (1), 4K (2, heavy for a small connection), unmarked (3), SD (4). */
    fun qualityRank(name: String): Int {
        val w = name.uppercase().split(' ', '(', ')', '[', ']', '|', '-').toSet()
        return when { "FHD" in w || "1080P" in w -> 0; "HD" in w || "720P" in w -> 1; "4K" in w || "UHD" in w -> 2; "SD" in w -> 4; else -> 3 }
    }

    const val EXACT = 1000
    const val START = 900
    const val WORD = 800
    const val INSIDE = 700
    const val ALL_WORDS = 600
    const val CLOSE = 400
    private val englishMark = Regex("[(\\[](EN|ENG|US|UK)[)\\]]", RegexOption.IGNORE_CASE)

    /** 0 when the title does not match; higher = better. */
    fun score(q: Query, e: Entry<*>): Int {
        if (!q.valid || e.compact.isEmpty()) return 0
        val level = when {
            e.norm == q.norm || e.compact == q.compact -> EXACT
            e.norm.startsWith(q.norm) || e.compact.startsWith(q.compact) -> START
            e.words.any { it.startsWith(q.compact) } || e.norm.contains(" " + q.norm) -> WORD
            e.compact.contains(q.compact) -> INSIDE
            q.words.size > 1 && q.words.all { w -> e.words.any { it.startsWith(w) } } -> ALL_WORDS
            q.typos > 0 && close(q, e) -> CLOSE
            else -> return 0
        }
        // Same level: the title nearer the query's length first ("Batman" before "Batman: The Killing Joke"); a copy in
        // another language after the French ones. English stays within its level: 400 off put "FROM (US)" after
        // every "From Scratch" and "Far from Home" though it is the title asked for.
        return level - (e.compact.length - q.compact.length).coerceIn(0, 60) - when { e.english -> 70; e.otherLanguage -> 400; else -> 0 }
    }

    /** The best [limit] entries, best first. */
    fun <T> search(q: Query, entries: List<Entry<T>>, limit: Int, bonus: (T) -> Int = { 0 }): List<T> {
        if (!q.valid) return emptyList()
        val scored = ArrayList<Pair<Entry<T>, Int>>()
        for (e in entries) {
            val s = score(q, e)
            if (s > 0) scored += e to s + bonus(e.item)
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first.item }
    }

    /** A typo away from the title's start or from one of its words ("spidrman", "batmann", "avangers"). */
    private fun close(q: Query, e: Entry<*>): Boolean {
        val first = q.compact[0]
        // A different first letter is rarely a typo: skip those titles (and most of the library) at once.
        if (first !in e.firstLetters()) return false
        val n = q.compact.length
        for (len in (n - 1)..(n + 1)) {
            if (len in 1..e.compact.length && distance(q.compact, e.compact.substring(0, len), q.typos) <= q.typos) return true
        }
        return e.words.any { w -> w.length >= 3 && kotlin.math.abs(w.length - n) <= q.typos && distance(q.compact, w, q.typos) <= q.typos }
    }

    /** Edits (insert, delete, change, swap two letters) between [a] and [b], stopping above [max]. */
    fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            prev.copyInto(prev2)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Whether a person's [name] answers [query]: each word of the query (2 letters or more) starts a word of the name,
     * accents and case aside. "tom holland", "holland" and "tom hol" → "Tom Holland"; "tom cruise" → not "Tom Holland".
     */
    fun nameAnswers(query: String, name: String): Boolean {
        val q = normalize(query).split(' ').filter { it.length >= 2 }
        if (q.isEmpty() || q.sumOf { it.length } < 3) return false
        val n = normalize(name).split(' ')
        return q.all { w -> n.any { it.startsWith(w) } }
    }

    /** Every word of [query] is a whole word of [name] ("tom holland" is Tom Holland, not Tom Hollander). */
    fun nameIs(query: String, name: String): Boolean {
        val n = normalize(name).split(' ').toSet()
        return normalize(query).split(' ').filter { it.isNotEmpty() }.let { q -> q.isNotEmpty() && q.all { it in n } }
    }
}
