package com.lumentv.data.repo

import com.lumentv.core.HttpBody
import com.lumentv.core.IoDispatcher
import com.lumentv.core.XmlReader
import com.lumentv.core.logWarn
import com.lumentv.core.nowMillis
import com.lumentv.data.db.transaction
import com.lumentv.core.Redact
import com.lumentv.core.SecretBox
import com.lumentv.core.channelKeyName
import com.lumentv.core.normalize
import com.lumentv.core.userMessage
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.EpgChannelEntity
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.db.PrefKind
import com.lumentv.data.db.ProgramEntity
import com.lumentv.data.epg.EpgMatcher
import com.lumentv.data.epg.XmltvParser
import com.lumentv.data.xtream.XtreamApi
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class NowNext(val now: ProgramEntity?, val next: ProgramEntity?)

class EpgRepository(
    private val db: AppDatabase,
    private val playlists: PlaylistRepository,
    private val epgDays: suspend () -> Int,
    /** Reads XMLTV from a body, with the encoding the file declares (xmlPullReaders(null) on Android). */
    private val newReader: (HttpBody) -> XmlReader,
) {
    private val matchers = Matchers()
    private val _busy = MutableStateFlow<Set<Long>>(emptySet())
    val busy: StateFlow<Set<Long>> = _busy
    private val _version = MutableStateFlow(0)
    /** Bumped after each import, so screens reload their guide data. */
    val version: StateFlow<Int> = _version

    /** The guide URL actually used: the user's, else the one in the M3U header, else Xtream's xmltv.php. */
    fun epgUrl(p: PlaylistEntity): String? {
        SecretBox.decrypt(p.epgUrlEnc).takeIf { it.isNotBlank() }?.let { return it }
        SecretBox.decrypt(p.autoEpgUrlEnc).takeIf { it.isNotBlank() }?.let { return it }
        if (p.type == PlaylistType.XTREAM) return XtreamApi.xmltvUrl(playlists.credentials(p))
        return null
    }

    suspend fun refresh(playlistId: Long): RefreshOutcome = withContext(IoDispatcher) {
        val p = db.playlists().get(playlistId) ?: return@withContext RefreshOutcome(false, "Playlist introuvable.")
        val url = epgUrl(p) ?: return@withContext RefreshOutcome(false, "Aucun guide TV (XMLTV) configuré pour cette playlist.")
        _busy.value = _busy.value + playlistId
        try {
            val result = import(p, url)
            matchers.remove(playlistId)
            _version.value++
            RefreshOutcome(true, result)
        } catch (t: Throwable) {
            logWarn(TAG, "epg failed: ${Redact.text(t.toString())}")
            RefreshOutcome(false, userMessage(t))
        } finally {
            _busy.value = _busy.value - playlistId
        }
    }

    private suspend fun import(p: PlaylistEntity, url: String): String {
        val wantedIds = db.channels().epgIds(p.id).toHashSet()
        db.prefs().allOf(p.id, PrefKind.CHANNEL).mapNotNullTo(wantedIds) { it.epgOverride?.lowercase() }
        val wantedNames = db.channels().names(p.id).mapTo(HashSet()) { channelKeyName(it) }
        val now = nowMillis()
        val maxCatchup = db.channels().inGroup(p.id, null).maxOfOrNull { it.ch.catchupDays } ?: 0
        val from = now - maxOf(1, maxCatchup).coerceAtMost(14) * DAY
        val to = now + epgDays().coerceIn(1, 14) * DAY
        val offset = p.epgOffsetMinutes * 60_000L

        val keep = HashSet<String>()
        val epgChannels = ArrayList<EpgChannelEntity>()
        val programs = ArrayList<ProgramEntity>()

        playlists.openSource(url, playlists.userAgentFor(p)).use { stream ->
            XmltvParser.parse(
                newReader(stream),
                onChannel = { c ->
                    val key = c.id.lowercase()
                    val byName = c.names.any { channelKeyName(it) in wantedNames }
                    if (key in wantedIds || byName) keep += key
                    // Every guide channel is listed (a few thousand small rows) so a wrong match can be corrected by hand.
                    c.names.ifEmpty { listOf(c.id) }.forEach { n -> epgChannels += EpgChannelEntity(p.id, key, n, channelKeyName(n)) }
                },
                onProgram = { pr ->
                    val key = pr.channel.lowercase()
                    val start = pr.start + offset
                    val stop = pr.stop + offset
                    if ((key in keep || key in wantedIds) && stop > from && start < to && programs.size < MAX_PROGRAMS) {
                        programs += ProgramEntity(
                            playlistId = p.id, epgKey = key, start = start, stop = stop, title = pr.title,
                            titleNorm = normalize(pr.title), description = pr.description?.take(800),
                        )
                    }
                },
            )
        }
        db.transaction {
            db.epg().deletePrograms(p.id)
            db.epg().deleteChannels(p.id)
            epgChannels.distinctBy { it.epgKey to it.nameKey }.chunked(500).forEach { db.epg().insertChannels(it) }
            programs.chunked(1000).forEach { db.epg().insertPrograms(it) }
            db.playlists().update(db.playlists().get(p.id)!!.copy(lastEpgRefresh = nowMillis()))
        }
        val matched = db.channels().inGroup(p.id, null).count { matcher(p.id).keyFor(it.epgOverride, it.ch.epgId, it.ch.name) != null }
        return "${programs.size} programmes · $matched chaînes associées"
    }

    suspend fun matcher(playlistId: Long): EpgMatcher {
        matchers[playlistId]?.let { return it }
        val rows = db.epg().channels(playlistId)
        val ids = rows.mapTo(HashSet()) { it.epgKey }
        val names = HashMap<String, String>()
        rows.forEach { if (it.nameKey !in names) names[it.nameKey] = it.epgKey }
        return EpgMatcher(ids, names).also { matchers[playlistId] = it }
    }

    fun invalidate(playlistId: Long) { matchers.remove(playlistId); _version.value++ }

    suspend fun keyFor(c: ChannelRow): String? = matcher(c.ch.playlistId).keyFor(c.epgOverride, c.ch.epgId, c.ch.name)

    suspend fun nowNext(c: ChannelRow, now: Long = nowMillis()): NowNext {
        val key = keyFor(c) ?: return NowNext(null, null)
        val list = db.epg().programs(c.ch.playlistId, key, now - 1, now + 12 * HOUR)
        val cur = list.firstOrNull { it.start <= now && it.stop > now }
        val next = list.firstOrNull { it.start >= (cur?.stop ?: now) }
        return NowNext(cur, next)
    }

    suspend fun programs(c: ChannelRow, from: Long, to: Long): List<ProgramEntity> {
        val key = keyFor(c) ?: return emptyList()
        return db.epg().programs(c.ch.playlistId, key, from, to).distinctBy { it.start to it.title }
    }

    /** Programmes of several channels at once, for the guide grid. Keyed by channel id. */
    suspend fun programsFor(rows: List<ChannelRow>, from: Long, to: Long): Map<Long, List<ProgramEntity>> {
        if (rows.isEmpty()) return emptyMap()
        val pid = rows.first().ch.playlistId
        val m = matcher(pid)
        val keyOf = rows.associate { it.ch.id to m.keyFor(it.epgOverride, it.ch.epgId, it.ch.name) }
        val keys = keyOf.values.filterNotNull().distinct()
        if (keys.isEmpty()) return emptyMap()
        val byKey = keys.chunked(400).flatMap { db.epg().programsFor(pid, it, from, to) }.groupBy { it.epgKey }
            .mapValues { (_, l) -> l.distinctBy { it.start to it.title } }
        return keyOf.mapValues { (_, k) -> k?.let { byKey[it] }.orEmpty() }
    }

    suspend fun epgChannelChoices(playlistId: Long): List<EpgChannelEntity> =
        db.epg().channels(playlistId).distinctBy { it.epgKey }.sortedBy { it.displayName.lowercase() }

    suspend fun clearAll() = withContext(IoDispatcher) {
        db.transaction { db.epg().clearPrograms(); db.epg().clearChannels() }
        matchers.clear(); _version.value++
    }

    suspend fun prune() = db.epg().deleteOlderThan(nowMillis() - 15 * DAY)

    /** The matchers by playlist, read from several coroutines at once. */
    private class Matchers {
        private val lock = SynchronizedObject()
        private val map = HashMap<Long, EpgMatcher>()
        operator fun get(id: Long): EpgMatcher? = synchronized(lock) { map[id] }
        operator fun set(id: Long, m: EpgMatcher) = synchronized(lock) { map[id] = m }
        fun remove(id: Long) = synchronized(lock) { map.remove(id) }
        fun clear() = synchronized(lock) { map.clear() }
    }

    companion object {
        private const val TAG = "Epg"
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        private const val MAX_PROGRAMS = 600_000
    }
}
