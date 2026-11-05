package com.lumentv.data.sports

import android.util.Log
import androidx.room.RoomRawQuery
import com.lumentv.core.normalize
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.ProgramEntity
import com.lumentv.data.repo.EpgRepository
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.settings.Settings
import com.lumentv.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

data class SportPrefs(val sports: Set<String>, val competitions: List<String>, val teams: List<String>, val days: Int,
                      /** Official calendar competitions; when some are chosen, only their real games are listed. */
                      val leagues: Set<String> = emptySet()) {
    companion object {
        fun of(s: Settings) = SportPrefs(s.sportIds, s.sportCompetitions, s.sportTeams, s.sportDays, s.sportLeagues)
    }
}

private const val TAG = "Sports"

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
        return db.epg().raw(RoomRawQuery(
            "SELECT * FROM programs WHERE playlistId = ? AND start > ? AND start < ? AND stop > ? AND ($where) ORDER BY start LIMIT 5000",
        ) { st ->
            listOf(pid, from - 6 * 3_600_000L, to, from).forEachIndexed { i, v -> st.bindLong(i + 1, v) }
            terms.forEachIndexed { i, t -> st.bindText(i + 5, t) }
        })
    }

    /** A game of the official calendar and the channels of the playlist that show it (possibly none yet). */
    data class Game(val fixture: Fixture, val channels: List<ChannelRow>,
                    /** The French channel showing it (xmltvfr.fr), e.g. "Ligue 1+ 2", and when it starts there. */
                    val tv: String? = null, val tvStart: Long = 0)

    /**
     * Channels announcing an event in their name, and channels by French name: going through the 13 000 channels takes
     * seconds on the box, so it is kept until the playlist is reloaded or a channel hidden.
     */
    private data class ChannelMaps(val named: List<Triple<ChannelRow, String, Long>>, val byKey: Map<String, List<ChannelRow>>)
    @Volatile private var maps: Triple<Long, String, ChannelMaps>? = null

    private suspend fun channelMaps(pid: Long): ChannelMaps = kotlinx.coroutines.coroutineScope {
        val version = "${db.playlists().get(pid)?.lastRefresh}:${library.changes.value}"
        maps?.let { (p, v, m) -> if (p == pid && v == version) return@coroutineScope m }
        val named = async {
            db.channels().withDates(pid).filter { !it.isHidden }.mapNotNull { r -> EventChannelParser.parse(r.ch.name)?.let { (l, t) -> Triple(r, normalize(l), t) } }
        }
        val byKey = async {
            db.channels().inGroup(pid, null).filter { !it.isHidden && FrenchTvGuide.frenchOrPlain(it.ch.name) }.groupBy { FrenchTvGuide.channelKey(it.ch.name) }
        }
        ChannelMaps(named.await(), byKey.await()).also { maps = Triple(pid, version, it) }
    }

    /** Official calendar, like the PC: every live and upcoming game of the chosen competitions, with its channels. */
    suspend fun games(pid: Long, prefs: SportPrefs): List<Game> = withContext(Dispatchers.IO) {
        val leagues = SportsFunnel.effectiveLeagues(prefs.sports, prefs.leagues)
        if (leagues.isEmpty()) return@withContext emptyList()
        val t0 = System.currentTimeMillis()
        fun lap(what: String) { if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "$what +${System.currentTimeMillis() - t0} ms") }
        // Calendar, French guide and the playlist's channels are independent: fetched together.
        val listingsAsync = async { runCatching { frenchGuide.listings() }.getOrDefault(emptyList()).also { lap("guide FR") } }
        val mapsAsync = async { channelMaps(pid).also { lap("channels") } }
        val indexAsync = async { channelsByKey(pid).also { lap("guide index") } }
        val list = fixtures.fixtures(leagues, SportsFunnel.days(prefs.days)).also { lap("calendar (${it.size})") }
        if (list.isEmpty()) return@withContext emptyList()
        val progs = candidates(pid, list.flatMap { FixtureMatcher.searchWords(it) }, list.minOf { it.start } - FixtureMatcher.BEFORE_MS,
            list.maxOf { it.start } + FixtureMatcher.AFTER_MS).also { lap("programmes") }
        val index = indexAsync.await()
        val (named, byKey) = mapsAsync.await()
        val listings = listingsAsync.await()
        // The French sports channels (those of the French guide): a fallback channel must be one of them.
        val sportKeys = listings.mapTo(HashSet()) { FrenchTvGuide.channelKey(it.channel) }
        list.map { f ->
            val shows = FrenchTvGuide.broadcasts(f, listings)
            val tv = shows.firstOrNull()
            val own = (progs.filter { FixtureMatcher.matches(f, it.titleNorm, it.start) }.flatMap { index[it.epgKey].orEmpty() } +
                named.filter { (_, l, t) -> FixtureMatcher.matches(f, l, t, channelName = true) }.map { it.first }).distinctBy { it.ch.id }
            // The French channel, when the playlist has it under the same name, becomes playable too.
            // Every French channel showing it live (Canal+ and Canal+ Live 1…), those the playlist has.
            val linked = shows.flatMap { l -> FrenchTvGuide.best(byKey[FrenchTvGuide.channelKey(l.channel)].orEmpty()) { r -> r.ch.name } }.distinctBy { it.ch.id }
            // The French guide (xmltvfr.fr) says who broadcasts the game in France: its channels in the playlist only. The
            // playlist's own guide is a fallback (a local channel's magazine can mention a game it does not show).
            val chosen = if (linked.isNotEmpty()) linked else own.filter { sportKeys.isEmpty() || FrenchTvGuide.channelKey(it.ch.name) in sportKeys }
            // French channels only: the panel is for a French family.
            Game(f, chosen.distinctBy { it.ch.id }.filter { FrenchTvGuide.french(it.ch.name, it.ch.groupName) }, tv?.channel, tv?.start ?: 0)
        }
    }

    /** Computes the list in the background at start-up, so the panel opens without waiting. */
    suspend fun prewarm(pid: Long) {
        runCatching { frenchGuide.refresh() }
        runCatching { games(pid, SportPrefs.of(settings.current())) }
    }

    suspend fun save(prefs: SportPrefs) = settings.setSports(prefs)
}
