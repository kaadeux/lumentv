package com.lumentv.data.sports

import com.lumentv.core.normalize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * A competition of the official calendar. [path] is ESPN's (free scoreboard, no key) or, when [sportsDb], a
 * TheSportsDB league id (free key) for what ESPN lacks. [sport] is a [SportsCatalog] id. [tvFr]: usual broadcaster in
 * France, shown when no channel of the playlist announces a game. [badgesFrom]: the same league on TheSportsDB, for the
 * club crests ESPN lacks or gets wrong (it gives Vannes the flag of Vanuatu, Perpignan the one of the USA).
 */
data class League(val id: String, val sport: String, val label: String, val path: String, val tvFr: String? = null, val sportsDb: Boolean = false, val badgesFrom: String? = null)

data class Team(val name: String, val short: String?, val abbr: String?, val logo: String?) {
    /** Names a guide may use for this team, normalised: "Paris Saint-Germain", "PSG", "Le Havre AC" → "le havre", "havre". */
    val aliases: List<String> by lazy {
        val out = LinkedHashSet<String>()
        listOfNotNull(name, short, abbr?.takeIf { it.length >= 3 }).forEach { n ->
            val x = normalize(n)
            out += x
            val stripped = x.split(' ').filter { it !in noise }.joinToString(" ")
            if (stripped.length >= 3) out += stripped
            stripped.split(' ').filter { it.length >= 5 }.maxByOrNull { it.length }?.let { out += it }
            // Short club prefixes dropped: "SU Agen Lot-et-Garonne" → "agen", "ASM Clermont Auvergne" → "clermont".
            x.split(' ').dropWhile { it.length <= 3 }.firstOrNull()?.takeIf { it.length >= 4 && it !in noise }?.let { out += it }
            // City + initials, as guides write it: "Paris Saint-Germain" → "paris sg".
            stripped.split(' ').takeIf { it.size >= 3 }?.let { w -> out += w[0] + " " + w.drop(1).joinToString("") { it.take(1) } }
        }
        out.filter { it.length >= 3 }
    }

    private companion object {
        val noise = setOf("fc", "ac", "as", "sc", "cf", "afc", "ssc", "us", "sco", "ogc", "rc", "stade", "olympique", "club", "de", "du", "la", "le", "les", "real", "sporting")
    }
}

data class Fixture(
    val id: String, val league: League, val start: Long,
    /** "pre" (upcoming) or "in" (live). Finished games are not kept. */
    val state: String, val detail: String,
    val home: Team?, val away: Team?, val name: String,
    val homeScore: String? = null, val awayScore: String? = null,
    /** "Journée 10" when the calendar says it, and the stadium ("Stade Océane, Le Havre"). */
    val round: String? = null, val venue: String? = null,
) {
    /** "⚽ Football · Ligue 1 · Journée 10" */
    val details get() = listOfNotNull(SportsCatalog.byId(league.sport)?.let { "${it.emoji} ${it.label}" }, league.label, round).joinToString(" · ")
    val live get() = state == "in"
    /** Where a live game stands: "67'", "Mi-temps", "QT3 5:21"… or "En cours" when the calendar does not say. */
    val clock get() = detail.ifBlank { "En cours" }
    /** "Habituellement sur Canal+", or null when unknown. */
    val usualBroadcaster get() = league.tvFr?.let { "Habituellement sur $it" }
    /** The channels can be opened during the game and from [OPEN_BEFORE_MS] before kick-off, not earlier. */
    fun channelsOpen(now: Long = System.currentTimeMillis()) = live || start - now <= OPEN_BEFORE_MS
    /** "Chaînes accessibles dès 20:00 (1 h avant le match)" */
    val opensNote get() = "Chaînes accessibles dès " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(java.util.Date(start - OPEN_BEFORE_MS)) + " (1 h avant le match)"
    val label get() = if (home != null && away != null) "${home.short ?: home.name} – ${away.short ?: away.name}" else name

    companion object {
        const val OPEN_BEFORE_MS = 3_600_000L
    }
}

/**
 * Upcoming and live games of the chosen competitions, from ESPN's public scoreboard: a few small requests
 * (about 15 KB per competition and day), kept 5 minutes. Replays never appear: only real games are listed.
 */
class FixtureClient(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, Pair<Long, List<Fixture>>>()

    suspend fun fixtures(leagues: Collection<League>, days: Int): List<Fixture> = withContext(Dispatchers.IO) {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        // ESPN days are US days: start one day back so tonight's European games are not missed.
        val dates = (-1..days.coerceIn(1, SportsFunnel.MAX_DAYS)).map { now + it * 86_400_000L }
        // Formatted here, once: SimpleDateFormat is not thread-safe, and the requests below run in parallel.
        val compact = dates.map { day.format(it) }
        val end = now + (days.coerceIn(1, SportsFunnel.MAX_DAYS) + 1) * 86_400_000L
        val rounds = days / 7 + 2
        coroutineScope {
            val crests = leagues.mapNotNull { l -> l.badgesFrom?.let { id -> id to async { rounds(l, id, rounds) } } }.toMap()
            leagues.flatMap { l ->
                if (l.sportsDb) listOf(async { rounds(l, l.path, rounds) })
                else compact.map { d -> async { day(l, d).let { list -> l.badgesFrom?.let { id -> withCrests(list, crests.getValue(id).await()) } ?: list } } }
            }.awaitAll().flatten()
        }.distinctBy { it.id }.filter { it.state == "in" || (it.state == "pre" && it.start > now - 3 * 3_600_000L && it.start < end) }
            .sortedWith(compareBy({ !it.live }, { it.start }))
    }

    private fun day(l: League, date: String): List<Fixture> =
        cached("${l.path}/$date") { get("https://site.api.espn.com/apis/site/v2/sports/${l.path}/scoreboard?dates=$date")?.let { parse(l, it) } }

    /**
     * The next [count] rounds of a TheSportsDB league: its free key gives at most 3 games per day request (Pro D2 plays
     * 8 on a Friday) but whole rounds, found from the next game's round number.
     */
    private fun rounds(l: League, id: String, count: Int): List<Fixture> = cached("sdb/$id/$count") {
        val next = get("https://www.thesportsdb.com/api/v1/json/123/eventsnextleague.php?id=$id")?.let { body ->
            ((json.parseToJsonElement(body) as? JsonObject)?.get("events") as? JsonArray)?.firstOrNull() as? JsonObject
        } ?: return@cached null
        val round = next.s("intRound")?.toIntOrNull() ?: return@cached null
        val season = next.s("strSeason") ?: return@cached null
        (round until round + count).flatMap { r ->
            parseSportsDb(l, get("https://www.thesportsdb.com/api/v1/json/123/eventsround.php?id=$id&r=$r&s=$season") ?: return@cached null)
        }
    }

    /** ESPN's missing or wrong crests (a country's flag for a club) replaced by TheSportsDB's for the same game. */
    private fun withCrests(list: List<Fixture>, other: List<Fixture>): List<Fixture> = list.map { f ->
        fun bad(t: Team?) = t != null && (t.logo == null || "/countries/" in t.logo)
        if (!bad(f.home) && !bad(f.away)) return@map f
        val same = other.firstOrNull { o ->
            kotlin.math.abs(o.start - f.start) < 2 * 3_600_000L && sameClub(f.home, o.home) && sameClub(f.away, o.away)
        } ?: return@map f.copy(home = f.home?.takeUnless { bad(it) } ?: f.home?.copy(logo = null), away = f.away?.takeUnless { bad(it) } ?: f.away?.copy(logo = null))
        f.copy(home = if (bad(f.home)) f.home?.copy(logo = same.home?.logo) else f.home, away = if (bad(f.away)) f.away?.copy(logo = same.away?.logo) else f.away)
    }

    /** "Bayonne" and "Aviron Bayonnais", "La Rochelle" and "Stade Rochelais": a common word start. */
    private fun sameClub(a: Team?, b: Team?): Boolean {
        fun stems(t: Team) = normalize(t.name).split(' ').filter { it.length >= 4 && it !in clubWords }.map { it.take(5) }.toSet()
        return a != null && b != null && stems(a).intersect(stems(b)).isNotEmpty()
    }

    /**
     * One more try on failure; a failure is not cached, so the next visit asks again instead of showing nothing. Kept
     * 5 minutes, but only [LIVE_REFRESH_MS] while one of its games is live or about to start: the score follows.
     */
    private fun cached(key: String, load: () -> List<Fixture>?): List<Fixture> {
        synchronized(cache) {
            cache[key]?.let { (t, list) ->
                val age = System.currentTimeMillis() - t
                if (age < (if (hasLive(list)) LIVE_REFRESH_MS - 2_000 else 5 * 60_000L)) return list
            }
        }
        val list = (1..2).firstNotNullOfOrNull { runCatching { load() }.getOrNull() } ?: return emptyList()
        synchronized(cache) { cache[key] = System.currentTimeMillis() to list }
        return list
    }

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body!!.string() else null }

    private fun parse(l: League, body: String): List<Fixture> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val events = (json.parseToJsonElement(body) as? JsonObject)?.get("events") as? JsonArray ?: return emptyList()
        return events.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val start = o.s("date")?.let { runCatching { iso.parse(it)!!.time }.getOrNull() } ?: return@mapNotNull null
            val status = (o["status"] as? JsonObject)?.get("type") as? JsonObject
            val state = status?.s("state") ?: "pre"
            if (state == "post") return@mapNotNull null
            val comp = (o["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject
            val sides = (comp?.get("competitors") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun side(where: String) = sides.firstOrNull { it.s("homeAway") == where }
            fun team(c: JsonObject?): Team? {
                val t = (c?.get("team") as? JsonObject) ?: (c?.get("athlete") as? JsonObject) ?: return null
                val name = t.s("displayName") ?: return null
                return Team(name, t.s("shortDisplayName"), t.s("abbreviation"), t.s("logo"))
            }
            Fixture(
                id = o.s("id") ?: return@mapNotNull null, league = l, start = start, state = state,
                detail = (if (state == "in") status?.s("shortDetail") else null)?.let { liveFr(it) }.orEmpty(),
                home = team(side("home")) ?: sides(o.s("name"))?.first, away = team(side("away")) ?: sides(o.s("name"))?.second, name = o.s("name").orEmpty(),
                homeScore = side("home")?.s("score")?.takeIf { state == "in" }, awayScore = side("away")?.s("score")?.takeIf { state == "in" },
                venue = (comp?.get("venue") as? JsonObject)?.let { v ->
                    listOfNotNull(v.s("fullName"), ((v["address"] as? JsonObject)?.s("city"))?.takeIf { c -> v.s("fullName")?.contains(c) != true }).joinToString(", ")
                }?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** TheSportsDB day list: no live state, so a game is "live" from kick-off for two hours unless marked finished. */
    private fun parseSportsDb(l: League, body: String): List<Fixture> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val events = (json.parseToJsonElement(body) as? JsonObject)?.get("events") as? JsonArray ?: return emptyList()
        val now = System.currentTimeMillis()
        return events.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val start = o.s("strTimestamp")?.let { runCatching { iso.parse(it.take(19))!!.time }.getOrNull() } ?: return@mapNotNull null
            val status = o.s("strStatus").orEmpty()
            if (status in setOf("FT", "AET", "AOT", "Match Finished", "CANC", "PST") || now > start + 2 * 3_600_000L) return@mapNotNull null
            val live = now >= start
            Fixture(
                id = "sdb" + (o.s("idEvent") ?: return@mapNotNull null), league = l, start = start, state = if (live) "in" else "pre",
                detail = if (live) sportsDbStatus(status, o.s("strProgress")) else "",
                home = o.s("strHomeTeam")?.let { Team(it, null, null, o.s("strHomeTeamBadge")) },
                away = o.s("strAwayTeam")?.let { Team(it, null, null, o.s("strAwayTeamBadge")) },
                name = o.s("strEvent").orEmpty(),
                homeScore = o.s("intHomeScore")?.takeIf { live }, awayScore = o.s("intAwayScore")?.takeIf { live },
                round = o.s("intRound")?.takeIf { it != "0" }?.let { "Journée $it" }, venue = o.s("strVenue"),
            )
        }
    }

    /** TheSportsDB's live status ("1H", "HT", "2H", and the minute when it gives one), or "" when it does not say. */
    private fun sportsDbStatus(status: String, progress: String?): String {
        val minute = progress?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.let { "$it'" }
        return when (status.uppercase()) {
            "HT" -> "Mi-temps"
            "1H" -> minute ?: "1re mi-temps"
            "2H" -> minute ?: "2e mi-temps"
            "ET" -> "Prolongations"
            "P", "PEN" -> "Tirs au but"
            else -> minute.orEmpty()
        }
    }

    /** ESPN's live status in French ("Halftime" → "Mi-temps", "2nd Half" → "2e mi-temps", "Q3 5:21" → "QT3 5:21"…). */
    internal fun liveFr(d: String): String {
        val t = d.trim()
        fun n(x: String) = x.replace(Regex("(st|nd|rd|th)$"), "").let { if (it == "1") "1re" else "${it}e" }
        Regex("(?i)^(1st|2nd|3rd|\\d+th)\\s+half$").find(t)?.let { return n(it.groupValues[1]) + " mi-temps" }
        Regex("(?i)^end of (1st|2nd|3rd|\\d+th)(\\s+\\w+)?$").find(t)?.let { return "Fin " + n(it.groupValues[1]).let { o -> if (o == "1re") "1er" else o } + " temps" }
        Regex("(?i)^(1st|2nd|3rd|\\d+th)\\s+(period|quarter)$").find(t)?.let { return n(it.groupValues[1]) + if (it.groupValues[2].lowercase() == "period") " période" else " quart-temps" }
        Regex("(?i)^q(\\d)\\b(.*)$").find(t)?.let { return "QT${it.groupValues[1]}${it.groupValues[2]}" }
        Regex("(?i)^(\\d+)(st|nd|rd|th)\\b(.*)$").find(t)?.let { return n(it.groupValues[1]) + it.groupValues[3] }
        Regex("(?i)^lap (.+)$").find(t)?.let { return "Tour ${it.groupValues[1]}" }
        Regex("(?i)^round (.+)$").find(t)?.let { return "Round ${it.groupValues[1]}" }
        return when (t.lowercase()) {
            "halftime", "ht", "half" -> "Mi-temps"
            "ft", "full time", "final" -> "Terminé"
            "et", "extra time" -> "Prolongations"
            "aet" -> "Après prolongations"
            "pen", "penalties", "shootout" -> "Tirs au but"
            "ot", "overtime" -> "Prolongation"
            "delayed" -> "Retardé"
            "postponed" -> "Reporté"
            "suspended" -> "Suspendu"
            "in progress" -> "En cours"
            else -> t
        }
    }

    /** "UFC Fight Night: Moicano vs. Nolan" → Moicano and Nolan (MMA events list their fights, not two teams). */
    private fun sides(name: String?): Pair<Team, Team>? {
        val main = name?.substringAfter(':', name)?.trim() ?: return null
        val parts = main.split(Regex("\\s+vs\\.?\\s+", RegexOption.IGNORE_CASE))
        if (parts.size != 2 || parts.any { it.isBlank() || it.length > 40 }) return null
        return Team(parts[0].trim(), null, null, null) to Team(parts[1].trim(), null, null, null)
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    companion object {
        /** How often a list with a live game is asked again (score, minute, half-time). */
        const val LIVE_REFRESH_MS = 30_000L

        /** A game live, or starting within 15 minutes: worth following closely. */
        fun hasLive(list: List<Fixture>, now: Long = System.currentTimeMillis()) = list.any { it.live || it.start - now in 0..15 * 60_000L }

        private val clubWords = setOf("stade", "union", "club", "rugby", "olympique", "sporting")
        val leagues = listOf(
            League("fra1", "football", "Ligue 1", "soccer/fra.1", "Ligue 1+ ou beIN Sports"),
            League("fra2", "football", "Ligue 2", "soccer/fra.2", "beIN Sports"),
            League("cdf", "football", "Coupe de France", "soccer/fra.coupe_de_france", "France Télévisions ou beIN Sports"),
            League("ucl", "football", "Ligue des champions", "soccer/uefa.champions", "Canal+"),
            League("uel", "football", "Ligue Europa", "soccer/uefa.europa", "Canal+"),
            League("uecl", "football", "Ligue Conférence", "soccer/uefa.europa.conf", "Canal+"),
            League("eng1", "football", "Premier League", "soccer/eng.1", "Canal+"),
            League("esp1", "football", "Liga", "soccer/esp.1", "beIN Sports"),
            League("ita1", "football", "Serie A", "soccer/ita.1", "beIN Sports"),
            League("ger1", "football", "Bundesliga", "soccer/ger.1", "beIN Sports"),
            League("top14", "rugby", "Top 14", "rugby/270559", "Canal+", badgesFrom = "4430"),
            League("prod2", "rugby", "Pro D2", "5172", "Canal+", sportsDb = true),
            League("rcc", "rugby", "Champions Cup (rugby)", "rugby/271937", "France Télévisions ou beIN Sports"),
            League("6n", "rugby", "Six Nations", "rugby/180659", "France 2"),
            League("nba", "basket", "NBA", "basketball/nba", "beIN Sports"),
            League("f1", "moteur", "Formule 1", "racing/f1", "Canal+"),
            League("nfl", "nfl", "NFL", "football/nfl"),
            League("nhl", "hockey", "NHL", "hockey/nhl"),
            League("ufc", "combat", "UFC", "mma/ufc"),
        )
        fun league(id: String) = leagues.firstOrNull { it.id == id }
    }
}

/** Finds, in a guide, the programmes that show a game of the official calendar. */
object FixtureMatcher {
    /** Window around the kick-off where a programme of the game must start. */
    const val BEFORE_MS = 45 * 60_000L
    const val AFTER_MS = 30 * 60_000L
    /** Event channels carry the time of their pre-show (or another time zone): a wider window. */
    const val CHANNEL_BEFORE_MS = 4 * 3_600_000L
    const val CHANNEL_AFTER_MS = 2 * 3_600_000L

    /** Words to pre-filter the guide with (team names and short names). */
    fun searchWords(f: Fixture): List<String> = listOfNotNull(f.home, f.away).flatMap { it.aliases }.ifEmpty {
        SportsCatalog.byId(f.league.sport)?.keywords.orEmpty()
    }

    /**
     * Whether a programme title (normalised) starting at [start] shows [f]: both teams named, or one team and the right
     * sport / competition; for races and fights (no teams), the sport's keywords. Replays fall outside the window.
     */
    fun matches(f: Fixture, titleNorm: String, start: Long, channelName: Boolean = false): Boolean {
        val before = if (channelName) CHANNEL_BEFORE_MS else BEFORE_MS
        val after = if (channelName) CHANNEL_AFTER_MS else AFTER_MS
        if (start < f.start - before || start > f.start + after) return false
        // A channel name must name both teams: one team is too weak on its own there.
        if (channelName && f.home != null && f.away != null) {
            val t = " $titleNorm "
            return listOf(f.home, f.away).all { team -> team.aliases.any { a -> t.contains(" $a ") || (a.length >= 6 && t.contains(a)) } }
        }
        val t = " $titleNorm "
        fun named(team: Team?) = team != null && team.aliases.any { a -> t.contains(" $a ") || (a.length >= 6 && t.contains(a)) }
        if (f.home != null && f.away != null) {
            val h = named(f.home); val a = named(f.away)
            if (h && a) return true
            if (!h && !a) return false
            val sport = SportsCatalog.byId(f.league.sport)
            return sport?.normKeywords?.any { t.contains(" $it ") || t.contains(it) } == true || t.contains(normalize(f.league.label))
        }
        return SportsCatalog.byId(f.league.sport)?.normKeywords?.any { t.contains(it) } == true
    }
}
