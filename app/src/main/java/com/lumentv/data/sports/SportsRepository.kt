package com.lumentv.data.sports

import androidx.sqlite.db.SimpleSQLiteQuery
import com.lumentv.core.normalize
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.ProgramEntity
import com.lumentv.data.repo.EpgRepository
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.settings.Settings
import com.lumentv.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SportEvent(
    val key: String,
    val sport: Sport,
    val competition: String?,
    val home: String?,
    val away: String?,
    val label: String,
    val title: String,
    val description: String?,
    val start: Long,
    val stop: Long,
    val channels: List<ChannelRow>,
    /** The programme on the first channel, for reminders and recordings. */
    val program: ProgramEntity,
) {
    fun isLive(now: Long = System.currentTimeMillis()) = start <= now && stop > now
}

data class SportPrefs(val sports: Set<String>, val competitions: List<String>, val teams: List<String>, val days: Int,
                      /** Official calendar competitions; when some are chosen, only their real games are listed. */
                      val leagues: Set<String> = emptySet()) {
    companion object {
        fun of(s: Settings) = SportPrefs(s.sportIds, s.sportCompetitions, s.sportTeams, s.sportDays, s.sportLeagues)
    }
}

/** Upcoming and live matches, taken from the guide (XMLTV) of the active playlist. */
private const val CACHE_MS = 120_000L

class SportsRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val epg: EpgRepository,
    private val library: LibraryRepository,
    private val fixtures: FixtureClient,
    private val frenchGuide: FrenchTvGuide,
) {
    /** epg key -> channels of the playlist that show it, rebuilt when the guide changes. */
    private var channelIndex: Triple<Long, Int, Map<String, List<ChannelRow>>>? = null

    private suspend fun channelsByKey(pid: Long): Map<String, List<ChannelRow>> {
        val version = epg.version.value + library.changes.value
        channelIndex?.let { (p, v, map) -> if (p == pid && v == version) return map }
        val m = epg.matcher(pid)
        val out = HashMap<String, MutableList<ChannelRow>>()
        db.channels().inGroup(pid, null).filter { !it.isHidden }.forEach { c ->
            m.keyFor(c.epgOverride, c.ch.epgId, c.ch.name)?.let { out.getOrPut(it) { ArrayList() } += c }
        }
        return out.also { channelIndex = Triple(pid, version, it) }
    }

    private suspend fun candidates(pid: Long, words: Collection<String>, from: Long, to: Long): List<ProgramEntity> {
        // instr() is much cheaper than LIKE '%x%', and stems keep the list short; the parser decides afterwards.
        val terms = words.map { normalize(it) }.filter { it.length >= 2 }.distinct()
            .let { list -> list.filter { t -> list.none { o -> o != t && o.length < t.length && t.contains(o) } } }
        if (terms.isEmpty()) return emptyList()
        val where = terms.joinToString(" OR ") { "instr(titleNorm, ?) > 0" }
        // The start index narrows the scan to the window: a programme longer than 6 h is not a match.
        val args = (listOf<Any>(pid, from - 6 * 3_600_000L, to, from) + terms).toTypedArray()
        return db.epg().raw(SimpleSQLiteQuery(
            "SELECT * FROM programs WHERE playlistId = ? AND start > ? AND start < ? AND stop > ? AND ($where) ORDER BY start LIMIT 5000", args,
        ))
    }

    suspend fun events(pid: Long, prefs: SportPrefs): List<SportEvent> = withContext(Dispatchers.IO) {
        if (prefs.leagues.isNotEmpty()) return@withContext official(pid, prefs)
        val now = System.currentTimeMillis()
        val sports = if (prefs.sports.isEmpty()) SportsCatalog.all else SportsCatalog.all.filter { it.id in prefs.sports }
        cache?.let { (k, t, list) -> if (k == Triple(pid, prefs, epg.version.value) && now - t < CACHE_MS) return@withContext list }
        val words = sports.flatMap { it.stems } + prefs.competitions + prefs.teams
        val progs = candidates(pid, words, now, now + prefs.days.coerceIn(1, 14) * 86_400_000L)
        val index = channelsByKey(pid)
        val comps = prefs.competitions.map { normalize(it) }.filter { it.isNotBlank() }
        val teams = prefs.teams.map { normalize(it) }.filter { it.isNotBlank() }
        val groups = LinkedHashMap<String, MutableList<Pair<ProgramEntity, ParsedEvent>>>()
        for (p in progs) {
            val e = EventParser.parse(p.title) ?: continue
            if (e.sport !in sports) continue
            if (comps.isNotEmpty() || teams.isNotEmpty()) {
                val t = " ${p.titleNorm} "
                if (comps.none { t.contains(it) } && teams.none { t.contains(" $it ") || t.contains(it) }) continue
            }
            // The same match on several channels: same label, starting within a quarter of an hour.
            val key = normalize(e.label) + "@" + (p.start / (15 * 60_000))
            groups.getOrPut(key) { ArrayList() } += p to e
        }
        val fromChannels = if (prefs.sports.isEmpty() || SportsCatalog.EVENT_ID in prefs.sports) eventChannels(pid, now, prefs, comps, teams) else emptyList()
        (fromChannels + groups.mapNotNull { (key, list) ->
            val channels = list.flatMap { (p, _) -> index[p.epgKey].orEmpty() }.distinctBy { it.ch.id }
            if (channels.isEmpty()) return@mapNotNull null
            val (p, e) = list.first()
            SportEvent(key, e.sport, e.competition, e.home, e.away, e.label, p.title, list.firstNotNullOfOrNull { it.first.description },
                list.minOf { it.first.start }, list.maxOf { it.first.stop }, channels, p)
        }).sortedWith(compareBy({ !it.isLive(now) }, { it.start })).also { cache = Triple(Triple(pid, prefs, epg.version.value), now, it) }
    }

    /** Matches announced by the channel name itself; they last about 3 hours. */
    private suspend fun eventChannels(pid: Long, now: Long, prefs: SportPrefs, comps: List<String>, teams: List<String>): List<SportEvent> {
        val until = now + prefs.days.coerceIn(1, 14) * 86_400_000L
        val generic = SportsCatalog.byId(SportsCatalog.EVENT_ID)!!
        val byLabel = LinkedHashMap<String, MutableList<Pair<ChannelRow, Long>>>()
        for (row in db.channels().withDates(pid)) {
            if (row.isHidden) continue
            val (label, start) = EventChannelParser.parse(row.ch.name) ?: continue
            if (start + 3 * 3_600_000L < now || start > until) continue
            val norm = normalize(label)
            if ((comps.isNotEmpty() || teams.isNotEmpty()) && comps.none { norm.contains(it) } && teams.none { norm.contains(it) }) continue
            byLabel.getOrPut(norm + "@" + start / (15 * 60_000)) { ArrayList() } += row to start
        }
        return byLabel.map { (key, list) ->
            val (row, start) = list.first()
            val label = EventChannelParser.parse(row.ch.name)!!.first
            val parsed = EventParser.parse(label)
            val stop = start + 3 * 3_600_000L
            SportEvent(
                key = "c:$key", sport = parsed?.sport ?: generic, competition = parsed?.competition ?: row.ch.groupName,
                home = parsed?.home, away = parsed?.away, label = parsed?.label ?: label, title = label,
                description = "Annoncé par le nom de la chaîne (heure lue dans le fuseau de la TV).",
                start = start, stop = stop, channels = list.map { it.first }.distinctBy { it.ch.id },
                program = ProgramEntity(playlistId = pid, epgKey = "", start = start, stop = stop, title = label, titleNorm = normalize(label)),
            )
        }
    }

    private var cache: Triple<Triple<Long, SportPrefs, Int>, Long, List<SportEvent>>? = null

    /** A game of the official calendar and the channels of the playlist that show it (possibly none yet). */
    data class Game(val fixture: Fixture, val channels: List<ChannelRow>,
                    /** The French channel showing it (xmltvfr.fr), e.g. "Ligue 1+ 2", and when it starts there. */
                    val tv: String? = null, val tvStart: Long = 0)

    /** Official calendar, like the PC: every live and upcoming game of the chosen competitions, with its channels. */
    suspend fun games(pid: Long, prefs: SportPrefs): List<Game> = withContext(Dispatchers.IO) {
        val leagues = SportsFunnel.effectiveLeagues(prefs.sports, prefs.leagues)
        if (leagues.isEmpty()) return@withContext emptyList()
        val list = fixtures.fixtures(leagues, SportsFunnel.DAYS)
        if (list.isEmpty()) return@withContext emptyList()
        val progs = candidates(pid, list.flatMap { FixtureMatcher.searchWords(it) }, list.minOf { it.start } - FixtureMatcher.BEFORE_MS,
            list.maxOf { it.start } + FixtureMatcher.AFTER_MS)
        val index = channelsByKey(pid)
        val named = db.channels().withDates(pid).filter { !it.isHidden }.mapNotNull { r -> EventChannelParser.parse(r.ch.name)?.let { (l, t) -> Triple(r, normalize(l), t) } }
        val listings = runCatching { frenchGuide.listings() }.getOrDefault(emptyList())
        val byKey = db.channels().inGroup(pid, null).filter { !it.isHidden && FrenchTvGuide.frenchOrPlain(it.ch.name) }.groupBy { FrenchTvGuide.channelKey(it.ch.name) }
        list.map { f ->
            val tv = FrenchTvGuide.broadcast(f, listings)
            val own = (progs.filter { FixtureMatcher.matches(f, it.titleNorm, it.start) }.flatMap { index[it.epgKey].orEmpty() } +
                named.filter { (_, l, t) -> FixtureMatcher.matches(f, l, t, channelName = true) }.map { it.first }).distinctBy { it.ch.id }
            // The French channel, when the playlist has it under the same name, becomes playable too.
            val linked = tv?.let { FrenchTvGuide.best(byKey[FrenchTvGuide.channelKey(it.channel)].orEmpty()) { r -> r.ch.name } }.orEmpty()
            Game(f, (own + linked).distinctBy { it.ch.id }, tv?.channel, tv?.start ?: 0)
        }
    }

    /**
     * Official calendar: the real live and upcoming games of the chosen competitions (ESPN), each with the channels of
     * the playlist whose guide shows it around kick-off, or whose name announces it. Replays never qualify.
     */
    private suspend fun official(pid: Long, prefs: SportPrefs): List<SportEvent> {
        val now = System.currentTimeMillis()
        val teams = prefs.teams.map { normalize(it) }.filter { it.isNotBlank() }
        val games = fixtures.fixtures(prefs.leagues.mapNotNull { FixtureClient.league(it) }, prefs.days)
            .filter { f -> teams.isEmpty() || listOfNotNull(f.home, f.away).any { t -> t.aliases.any { a -> teams.any { a.contains(it) || it.contains(a) } } } }
        if (games.isEmpty()) return emptyList()
        val progs = candidates(pid, games.flatMap { FixtureMatcher.searchWords(it) }, games.minOf { it.start } - FixtureMatcher.BEFORE_MS,
            games.maxOf { it.start } + FixtureMatcher.AFTER_MS)
        val index = channelsByKey(pid)
        val named = db.channels().withDates(pid).filter { !it.isHidden }.mapNotNull { r -> EventChannelParser.parse(r.ch.name)?.let { (l, t) -> Triple(r, normalize(l), t) } }
        val generic = SportsCatalog.byId(SportsCatalog.EVENT_ID)!!
        return games.mapNotNull { f ->
            val shown = progs.filter { FixtureMatcher.matches(f, it.titleNorm, it.start) }
            val channels = (shown.flatMap { index[it.epgKey].orEmpty() } + named.filter { (_, l, t) -> FixtureMatcher.matches(f, l, t, channelName = true) }.map { it.first })
                .distinctBy { it.ch.id }
            if (channels.isEmpty()) return@mapNotNull null
            val start = if (f.live) minOf(f.start, now) else f.start
            val stop = maxOf(start + 2 * 3_600_000L, if (f.live) now + 30 * 60_000L else 0)
            val score = if (f.live && f.homeScore != null) "${f.home?.short ?: f.home?.name} ${f.homeScore} – ${f.awayScore} ${f.away?.short ?: f.away?.name}" else f.label
            SportEvent(
                key = "f:${f.id}", sport = SportsCatalog.byId(f.league.sport) ?: generic, competition = f.league.label,
                home = f.home?.name, away = f.away?.name, label = score, title = shown.firstOrNull()?.title ?: f.label,
                description = listOfNotNull(f.detail.takeIf { it.isNotBlank() }, "Calendrier officiel").joinToString(" · "),
                start = start, stop = stop, channels = channels,
                program = shown.firstOrNull() ?: ProgramEntity(playlistId = pid, epgKey = "", start = start, stop = stop, title = f.label, titleNorm = normalize(f.label)),
            )
        }.sortedWith(compareBy({ !it.isLive(now) }, { it.start }))
    }

    /** Computes the list in the background at start-up, so the panel opens without waiting. */
    suspend fun prewarm(pid: Long) { runCatching { events(pid, SportPrefs.of(settings.current())) } }

    /** Competitions and teams that appear in the guide, most frequent first, to pick from in the settings. */
    suspend fun suggestions(pid: Long, sportIds: Set<String>): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val sports = if (sportIds.isEmpty()) SportsCatalog.all else SportsCatalog.all.filter { it.id in sportIds }
        val progs = candidates(pid, sports.flatMap { it.stems }, now, now + 7 * 86_400_000L)
        val comps = HashMap<String, Int>()
        val teams = HashMap<String, Int>()
        progs.mapNotNull { EventParser.parse(it.title) }.filter { it.sport in sports }.forEach { e ->
            e.competition?.let { comps.merge(it, 1, Int::plus) }
            e.home?.let { teams.merge(it, 1, Int::plus) }
            e.away?.let { teams.merge(it, 1, Int::plus) }
        }
        comps.entries.sortedByDescending { it.value }.take(40).map { it.key } to
            teams.entries.sortedByDescending { it.value }.take(60).map { it.key }
    }

    suspend fun save(prefs: SportPrefs) = settings.setSports(prefs)
}
