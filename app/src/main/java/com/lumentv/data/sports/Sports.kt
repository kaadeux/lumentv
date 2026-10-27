package com.lumentv.data.sports

import com.lumentv.core.normalize

data class Sport(val id: String, val label: String, val emoji: String, val keywords: List<String>, val individual: Boolean,
                 /** Short substrings for the database pre-filter; the exact keywords decide afterwards. */
                 val stems: List<String> = keywords) {
    /** Keywords in search form ("formule 1"), longest first so the most specific one wins. */
    val normKeywords: List<String> = keywords.map { normalize(it) }.sortedByDescending { it.length }
}

object SportsCatalog {
    val all = listOf(
        Sport("football", "Football", "⚽", stems = listOf("foot", "soccer", "ligue", "league", "liga", "serie a", "bundesliga", "coupe", "cup", "mls", "eredivisie"), keywords = listOf("football", "foot", "soccer", "ligue 1", "ligue 2", "premier league", "liga", "serie a",
            "bundesliga", "champions league", "ligue des champions", "europa league", "conference league", "coupe de france", "fa cup",
            "coupe du monde", "euro 2028", "mls", "eredivisie", "liga portugal", "saudi pro league"), individual = false),
        Sport("nfl", "Football américain", "🏈", listOf("football americain", "nfl", "super bowl", "ncaa football"), false),
        Sport("rugby", "Rugby", "🏉", listOf("rugby", "top 14", "pro d2", "six nations", "6 nations", "champions cup", "challenge cup", "urc"), false),
        Sport("tennis", "Tennis", "🎾", listOf("tennis", "atp", "wta", "roland garros", "wimbledon", "us open", "open d australie", "coupe davis",
            "masters 1000"), true),
        Sport("basket", "Basket", "🏀", listOf("basket", "basketball", "nba", "euroleague", "euroligue", "betclic elite", "wnba"), false),
        Sport("moteur", "F1 / Moteur", "🏎", listOf("formule 1", "formula 1", "f1", "grand prix", "motogp", "moto gp", "rallye", "wrc", "nascar",
            "indycar", "endurance", "wec", "formule 2", "formule e"), true),
        Sport("baseball", "Baseball", "⚾", listOf("baseball", "mlb"), false),
        Sport("hockey", "Hockey sur glace", "🏒", listOf("hockey", "nhl"), false),
        Sport("handball", "Handball", "🤾", listOf("handball", "starligue", "ehf"), false),
        Sport("volley", "Volley", "🏐", listOf("volley", "volleyball"), false),
        Sport("cyclisme", "Cyclisme", "🚴", listOf("cyclisme", "tour de france", "giro", "vuelta", "paris roubaix", "milan san remo"), true),
        Sport("golf", "Golf", "⛳", listOf("golf", "pga", "ryder cup", "dp world tour"), true),
        Sport("combat", "Boxe / MMA", "🥊", listOf("boxe", "boxing", "mma", "ufc", "pfl", "bellator"), true),
        Sport("athle", "Athlétisme / JO", "🏅", listOf("athletisme", "diamond league", "jeux olympiques", "marathon"), true),
        /** Matches found in channel names ("PRIME FR 022| Havre vs. PSG (2026-10-30 17:28:00)"), sport unknown. */
        Sport(EVENT_ID, "Chaînes événement", "🏟", emptyList(), false),
    )

    const val EVENT_ID = "event"

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

data class ParsedEvent(val sport: Sport, val competition: String?, val home: String?, val away: String?, val label: String)

/** Reads "Football : Ligue 2 BKT | Rodez / Annecy", "Ligue 1 - PSG vs OM", "Tennis : ATP Paris | Sinner - Alcaraz". */
/** Pay-per-view style channels whose name carries the match and its date. */
object EventChannelParser {
    private val stamp = Regex("\\(?(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::\\d{2})?\\)?")

    /** (label, start epoch ms) — the date is read in the device's time zone. */
    fun parse(name: String): Pair<String, Long>? {
        val m = stamp.find(name) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt())
        // "PRIME FR 022| Havre vs. PSG (...)": drop the provider prefix before the last "|" and the date.
        val label = name.removeRange(m.range).substringAfterLast('|').replace(Regex("\\s+"), " ").trim().trim('-', ':', ' ')
        if (label.length < 3) return null
        return label to cal.timeInMillis
    }
}

object EventParser {
    /** Between two teams. " x " is how Brazilian and Portuguese guides write it ("Estoril x Santa Clara"). */
    private val strong = listOf(" / ", " vs. ", " vs ", " x ", " v ", " contre ")
    private val dashes = listOf(" - ", " – ", " — ")
    private val episodeMark = Regex("\\b[STst]\\d{1,2} ?[Ee]\\d{1,3}\\b")
    private val notMatches = listOf("magazine", "resume", "resumes", "replay", "rediffusion", "highlights", "debrief", "avant match",
        "apres match", "le mag", "talk", "journal", "jt", "les buts", "best of", "multiplex des buts", "documentaire", "portrait",
        "retro", "classique", "l equipe du soir", "canal football club", "late football club", "rmc", "info sport")

    fun sportOf(title: String): Sport? {
        val padded = " ${normalize(title)} "
        var best: Pair<Sport, Int>? = null
        for (s in SportsCatalog.all) for (k in s.normKeywords) {
            if (padded.contains(" $k ")) { if (best == null || k.length > best.second) best = s to k.length; break }
        }
        return best?.first
    }

    /** Kodi formatting some providers leave in titles: "[COLOR tomato]T2026[/COLOR]", "[B]…[/B]". */
    private val kodiTags = Regex("\\[/?(?:COLOR|B|I|UPPERCASE|LOWERCASE)[^\\]]*\\]", RegexOption.IGNORE_CASE)

    fun parse(rawTitle: String): ParsedEvent? {
        val title = rawTitle.replace(kodiTags, "").replace(Regex("\\s{2,}"), " ").trim()
        val sport = sportOf(title) ?: return null
        val norm = " ${normalize(title)} "
        if (notMatches.any { norm.contains(" $it ") }) return null
        // "Transporter T2 E2 Vuelta al pasado" is a TV series episode, not a race.
        if (episodeMark.containsMatchIn(title)) return null
        var rest = title
        var competition: String? = null
        // "Sport : Competition | Teams"
        val colon = rest.indexOf(" : ").takeIf { it >= 0 } ?: rest.indexOf(": ").takeIf { it in 1..30 }
        if (colon != null && colon >= 0) {
            val head = rest.substring(0, colon)
            if (sportOf(head) != null) rest = rest.substring(colon + if (rest.startsWith(" : ", colon)) 3 else 2)
        }
        val pipe = rest.indexOf(" | ")
        if (pipe >= 0) { competition = rest.substring(0, pipe).trim(); rest = rest.substring(pipe + 3) }
        val found = teams(rest, sport.individual)
        val home = found?.first
        val away = found?.second
        if (found?.third != null && competition == null) competition = found.third
        if (home == null && !sport.individual) return null
        val label = if (home != null) "$home – $away" else rest.trim()
        return ParsedEvent(sport, competition?.takeIf { it.isNotBlank() }, home, away, label)
    }

    /** (home, away, competition found around the teams). */
    private fun teams(text: String, individual: Boolean): Triple<String, String, String?>? {
        val t = text.substringBefore(" (").trim()
        // A clear "A / B", "A vs B" or "A x B" first; a competition may sit before or after it behind a dash.
        for (sep in strong) {
            if (individual && sep.trim() !in setOf("/", "vs", "vs.")) continue
            val i = t.indexOf(sep)
            if (i <= 0) continue
            var a = t.substring(0, i).trim()
            var b = t.substring(i + sep.length).trim()
            var comp: String? = null
            dashes.firstOrNull { b.contains(it) }?.let { d -> comp = b.substringAfter(d).trim(); b = b.substringBefore(d).trim() }
            (dashes + ": ").firstOrNull { a.contains(it) }?.let { d ->
                if (comp == null) comp = a.substringBeforeLast(d).trim()
                a = a.substringAfterLast(d).trim()
            }
            if (ok(a) && ok(b)) return Triple(a, b, comp?.takeIf { it.isNotBlank() })
        }
        // "Grand Prix du Mexique - Course" is not a duel: individual sports never split on a dash.
        if (individual) return null
        for (d in dashes) {
            val i = t.lastIndexOf(d)
            if (i <= 0) continue
            var a = t.substring(0, i).trim()
            val b = t.substring(i + d.length).trim()
            var comp: String? = null
            // "Ligue 1 - PSG - OM": the chunk before the home team is the competition.
            (dashes + ": ").firstOrNull { a.contains(it) }?.let { d2 -> comp = a.substringBeforeLast(d2).trim(); a = a.substringAfterLast(d2).trim() }
            if (ok(a) && ok(b)) return Triple(a, b, comp)
        }
        return null
    }

    private fun ok(s: String) = s.length in 2..40 && s.count { it == ' ' } <= 5
}
