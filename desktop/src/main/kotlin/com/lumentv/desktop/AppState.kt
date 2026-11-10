package com.lumentv.desktop

import com.lumentv.core.asHttp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumentv.core.userMessage
import com.lumentv.sync.ProfileDoc
import com.lumentv.sync.ProfileKeys
import com.lumentv.sync.ProfileMerge
import com.lumentv.sync.ProfileSync
import com.lumentv.sync.SyncFavorite
import com.lumentv.sync.SyncHistory
import com.lumentv.sync.SyncPlaylist
import com.lumentv.sync.SyncValue
import com.lumentv.sync.WrongPassphraseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

object Kind { const val CHANNEL = "ch"; const val MOVIE = "mv"; const val SERIES = "se"; const val EPISODE = "ep" }

/**
 * Everything the window shows. On the PC the profile document *is* the local data: each change is dated right away,
 * so syncing is a plain merge with the server copy.
 */
class AppState(private val scope: CoroutineScope) {
    init { com.lumentv.core.PrivateNet.init(java.io.File(Store.dir, "https_hosts.txt")) }
    /** Encrypted DNS and HTTPS whenever the server allows it (see PrivateNet): the internet provider sees less. */
    val http: OkHttpClient = with(com.lumentv.core.PrivateNet) { OkHttpClient.Builder().private() }.connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(true).build()
    val library = Library(http)
    /** TMDB: rows of the Films / Séries homes, logos, summaries, cast, search by person. */
    val vod = Vod(this)
    /** Official sports calendar (live and upcoming games). */
    val fixtures = com.lumentv.data.sports.fixtureClient(http)
    /** Which French channel shows each game, days ahead (xmltvfr.fr, sports channels only, twice a day at most). */
    val frenchGuide = com.lumentv.data.sports.FrenchTvGuide(http.asHttp(), com.lumentv.core.TextFile(java.io.File(Store.dir, "sports_tv.json").path),
        com.lumentv.core.xmlPullReaders { org.kxml2.io.KXmlParser() })
    private val sync = ProfileSync(http.asHttp(), BuildInfo.SYNC_URL)
    private val prefs = Preferences.userRoot().node("lumentv")
    private val syncLock = Mutex()

    /** Tells the other Lumen TV devices of the same subscription that this PC is playing. */
    val presence = com.lumentv.data.xtream.Presence(http.asHttp(), BuildInfo.SYNC_URL,
        prefs.get("device", null) ?: java.util.UUID.randomUUID().toString().also { prefs.put("device", it) },
        "PC " + (System.getenv("COMPUTERNAME") ?: "Windows"))
    fun presenceKey(): String? = active?.let { com.lumentv.data.xtream.Presence.key(it.type, it.source, it.username, it.password) }

    val downloads = Downloads(scope, http, presence) { key -> doc.playlists[key]?.let { com.lumentv.data.xtream.Presence.key(it.type, it.source, it.username, it.password) } }

    /** Queues a film or an episode in the downloads. */
    fun download(item: PlayItem) {
        val k = activeKeyOrFirst ?: return
        downloads.add(item.kind, item.uid, k, item.title, item.subtitle, item.image, item.parentUid, item.url, item.userAgent, item.tmdb, item.season, item.episode)
        show("Ajouté aux téléchargements")
    }

    /** "Marquer comme vu" (or not) for several films / episodes at once. */
    fun markWatched(items: List<PlayItem>, durations: List<Long>, watched: Boolean) {
        val k = activeKeyOrFirst ?: return
        val now = now()
        change { d ->
            d.copy(history = d.history + items.mapIndexed { i, it ->
                val dur = durations[i].coerceAtLeast(1)
                ProfileMerge.itemKey(k, it.kind, it.uid) to SyncHistory(k, it.kind, it.uid, it.title, it.subtitle, it.image, it.parentUid,
                    now - (items.size - i), if (watched) dur else 0, if (watched) dur else 0, deleted = !watched, at = now)
            })
        }
    }

    fun isWatched(kind: String, uid: String): Boolean = progress(kind, uid)?.let { it.durationMs > 0 && it.positionMs >= minOf(it.durationMs * 92 / 100, it.durationMs - 60_000) } == true

    var doc by mutableStateOf(Store.loadDoc()); private set
    var profileName by mutableStateOf(Store.loadKeys()?.name); private set
    var syncing by mutableStateOf(false); private set
    var syncMessage by mutableStateOf<String?>(null); private set
    var lastSync by mutableStateOf(prefs.getLong("lastSync", 0)); private set

    var activeKey by mutableStateOf(prefs.get("active", null)); private set
    var catalog by mutableStateOf<Catalog?>(null); private set
    var loading by mutableStateOf(false); private set
    /** The step of the list being loaded ("Chargement des films · 12 000") and how far along, for the bar at the top. */
    var loadStep by mutableStateOf<Pair<String, Float?>?>(null); private set
    var loadError by mutableStateOf<String?>(null); private set
    var guide by mutableStateOf<Map<String, List<Prog>>>(emptyMap()); private set
    var toast by mutableStateOf<String?>(null)

    val syncEnabled get() = BuildInfo.SYNC_URL.isNotEmpty()
    val playlists get() = doc.playlists.filterValues { !it.deleted }
    val active: SyncPlaylist? get() = activeKey?.let { playlists[it] } ?: playlists.values.firstOrNull()
    internal val activeKeyOrFirst get() = activeKey?.takeIf { it in playlists } ?: playlists.keys.firstOrNull()

    fun setting(key: String, default: String = "") = doc.settings[key]?.value ?: default
    fun setSetting(key: String, value: String) = change { it.copy(settings = it.settings + (key to SyncValue(value, now()))) }

    init { PcSettings.read = { key, default -> setting(key, default) } }

    // Whose PC a crash report comes from: the profile signed in, and this PC's id.
    init {
        com.lumentv.core.CrashReports.deviceId = prefs.get("device", "")
        scope.launch { androidx.compose.runtime.snapshotFlow { profileName }.collect { com.lumentv.core.CrashReports.profile = it.orEmpty() } }
    }
    // A crash of an earlier session, sent to the sync server where the developer reads it.
    init { scope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(5_000); com.lumentv.core.CrashReports.send(crashFile, http.asHttp(), BuildInfo.SYNC_URL) } }

    val parental = PcParental(this)

    /** The active list without what the parental control hides (see [PcParental]). */
    val shownCatalog: Catalog? by androidx.compose.runtime.derivedStateOf {
        val c = catalog ?: return@derivedStateOf null
        val hidden = parental.hiddenNow()
        if (hidden.isEmpty()) c else c.copy(
            channels = c.channels.filter { "c:" + it.uid !in hidden && "g:" + it.group !in hidden },
            movies = c.movies.filter { "m:" + it.category !in hidden },
            series = c.series.filter { "s:" + it.category !in hidden },
        )
    }

    /** Remembered for "Au lancement": on this PC only (a channel is not the same on another device's list). */
    fun rememberChannel(uid: String) = prefs.put("last_channel", uid)

    fun show(msg: String) { toast = msg; scope.launch { delay(3500); if (toast == msg) toast = null } }

    // ---- Playlists ------------------------------------------------------------------------------------------

    fun start() {
        // Loads the VLC engine now, in the background, so the first playback does not wait for it.
        Thread { runCatching { Vlc.factory } }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        activeKeyOrFirst?.let { open(it) }
        // TMDB's cache over its limit (lowered in 1.7.2: 40 → 20 MB) is emptied once.
        scope.launch(Dispatchers.IO) { runCatching { com.lumentv.data.TmdbHttp.trimCache(vod.tmdbHttp) } }
        if (profileName != null) scope.launch { syncNow() }
        scope.launch { while (true) { delay(10 * 60_000L); if (profileName != null) syncNow() } }
    }

    fun addPlaylist(p: SyncPlaylist) {
        val key = ProfileMerge.playlistKey(p.type, p.source, p.username)
        change { it.copy(playlists = it.playlists + (key to p.copy(at = now()))) }
        open(key, force = true)
    }

    /** A list changed: its key follows its server and user name. [reload]: read again (not for a mere reload interval). */
    fun replacePlaylist(key: String, p: SyncPlaylist, reload: Boolean = true) {
        val newKey = ProfileMerge.playlistKey(p.type, p.source, p.username)
        change { d ->
            val gone = if (newKey != key) d.playlists[key]?.let { mapOf(key to it.copy(deleted = true, at = now())) }.orEmpty() else emptyMap()
            d.copy(playlists = d.playlists + gone + (newKey to p.copy(at = now())))
        }
        if (reload) open(newKey, force = true) else if (activeKey == key && newKey != key) activeKey = newKey
    }

    /** The guide read again ("epg_days" days ahead). */
    fun reloadGuide() {
        val c = catalog ?: return
        val p = active ?: return
        scope.launch { runCatching { library.guide(c, p.epgOffsetMinutes, p.ua, guideDays) }.onSuccess { guide = it } }
    }

    fun clearGuide() { guide = emptyMap() }

    private val guideDays get() = setting("epg_days", "3").toIntOrNull() ?: 3

    /** Setting "Au lancement": the last channel, once, when the list is in. */
    private var startupChannelPending = setting("startup") == "last"

    private fun playStartupChannel(c: Catalog) {
        if (!startupChannelPending) return
        startupChannelPending = false
        val uid = prefs.get("last_channel", null) ?: return
        c.channels.firstOrNull { it.uid == uid }?.let { playChannel(it) }
    }

    fun removePlaylist(key: String) {
        val p = doc.playlists[key] ?: return
        change { it.copy(playlists = it.playlists + (key to p.copy(deleted = true, at = now()))) }
        if (activeKey == key) { activeKey = null; catalog = null; guide = emptyMap(); activeKeyOrFirst?.let { open(it) } }
    }

    private var loadJob: Job? = null

    /** Shows a playlist: from the cache at once, downloaded again when older than its refresh interval. */
    fun open(key: String, force: Boolean = false) {
        val p = playlists[key] ?: return
        activeKey = key; prefs.put("active", key)
        loadJob?.cancel()
        loadJob = scope.launch {
            loadError = null
            loading = true
            val cached = withContext(Dispatchers.IO) { Store.loadCatalog(key) }
            catalog = cached
            val stale = cached == null || force || cached.version < CATALOG_VERSION || System.currentTimeMillis() - cached.loadedAt > p.refreshHours.coerceAtLeast(1) * 3_600_000L
            if (stale) {
                runCatching { library.load(p) { label, f -> loadStep = label to f } }
                    .onSuccess { c -> catalog = c; withContext(Dispatchers.IO) { Store.saveCatalog(key, c) } }
                    .onFailure { loadError = userMessage(it) }
            }
            loading = false
            if (System.getenv("LUMEN_PROBE") == "1") catalog?.let { c -> withContext(Dispatchers.IO) { library.probe(p, c) } }
            // A list read from the cache, from before the account was kept: its state asked alone (end date in Settings).
            catalog?.takeIf { p.type == "xtream" && it.account == null }?.let { c ->
                runCatching { library.account(p) }.getOrNull()?.let { a ->
                    val withAccount = c.copy(account = a)
                    if (activeKey == key) catalog = withAccount
                    withContext(Dispatchers.IO) { Store.saveCatalog(key, withAccount) }
                }
            }
            catalog?.let { c ->
                playStartupChannel(c)
                if (stale || guide.isEmpty()) loadStep = "Chargement du guide TV" to null
                runCatching { library.guide(c, p.epgOffsetMinutes, p.ua, guideDays) }.onSuccess { guide = it }
            }
            loadStep = null
        }
    }

    fun reload() { activeKeyOrFirst?.let { open(it, force = true) } }

    // ---- Favourites and history ---------------------------------------------------------------------------

    fun isFavorite(kind: String, uid: String): Boolean {
        val k = activeKeyOrFirst ?: return false
        return doc.favorites[ProfileMerge.itemKey(k, kind, uid)]?.on == true
    }

    fun toggleFavorite(kind: String, uid: String): Boolean {
        val k = activeKeyOrFirst ?: return false
        val key = ProfileMerge.itemKey(k, kind, uid)
        val on = doc.favorites[key]?.on != true
        val order = (doc.favorites.values.maxOfOrNull { it.order } ?: 0) + 1
        change { it.copy(favorites = it.favorites + (key to SyncFavorite(k, kind, uid, if (on) order else 0, on, now()))) }
        show(if (on) "Ajouté aux favoris" else "Retiré des favoris")
        return on
    }

    fun favorites(kind: String): List<SyncFavorite> {
        val k = activeKeyOrFirst ?: return emptyList()
        return doc.favorites.values.filter { it.playlist == k && it.kind == kind && it.on }.sortedBy { it.order }
    }

    /** Films and series only (no live channels), one line per series: its last episode watched. */
    fun history(): List<SyncHistory> {
        val k = activeKeyOrFirst ?: return emptyList()
        return doc.history.values.filter { it.playlist == k && !it.deleted && it.kind != Kind.CHANNEL }.sortedByDescending { it.watchedAt }
            .distinctBy { if (it.kind == Kind.EPISODE) "s:" + (it.parentUid ?: it.uid) else it.kind + it.uid }
    }

    fun progress(kind: String, uid: String): SyncHistory? {
        val k = activeKeyOrFirst ?: return null
        return doc.history[ProfileMerge.itemKey(k, kind, uid)]?.takeIf { !it.deleted }
    }

    fun saveProgress(item: PlayItem, positionMs: Long, durationMs: Long) {
        if (System.getenv("LUMEN_NO_HISTORY") != null || item.catchup != null) return // tests: leave the real history alone
        val k = activeKeyOrFirst ?: return
        val key = ProfileMerge.itemKey(k, item.kind, item.uid)
        val h = SyncHistory(k, item.kind, item.uid, item.title, item.subtitle, item.image, item.parentUid, now(),
            if (item.live) 0 else positionMs, if (item.live) 0 else durationMs, at = now())
        change(sync = false) { it.copy(history = it.history + (key to h)) }
    }

    /** Takes items out of the history (and so out of "Reprendre la lecture"), on every device of the profile. */
    fun forgetHistory(kind: String, uids: Collection<String>) {
        val k = activeKeyOrFirst ?: return
        change { d -> d.copy(history = d.history + uids.mapNotNull { u -> ProfileMerge.itemKey(k, kind, u).let { key -> d.history[key]?.let { key to it.copy(deleted = true, at = now()) } } }) }
    }

    /** Films, or episodes, of the current playlist still in the history, the last watched first. */
    fun historyOf(kind: String): List<SyncHistory> {
        val k = activeKeyOrFirst ?: return emptyList()
        return doc.history.values.filter { it.playlist == k && !it.deleted && it.kind == kind }.sortedByDescending { it.watchedAt }
    }

    fun clearHistory() = change { d -> d.copy(history = d.history.mapValues { (_, h) -> if (h.playlist == activeKeyOrFirst) h.copy(deleted = true, at = now()) else h }) }

    /** A change of the profile document made elsewhere (ratings), saved and synced like the others. */
    internal fun updateDoc(f: (ProfileDoc) -> ProfileDoc) = change(f = f)

    private var syncSoon: Job? = null

    private fun change(sync: Boolean = true, f: (ProfileDoc) -> ProfileDoc) {
        doc = f(doc)
        Store.saveDoc(doc)
        if (profileName != null) {
            syncSoon?.cancel()
            syncSoon = scope.launch { delay(if (sync) 5_000 else 60_000); syncNow() }
        }
    }

    // ---- Online profile ------------------------------------------------------------------------------------

    suspend fun signIn(name: String, passphrase: String): Result<Unit> = runCatching {
        check(playlists.isNotEmpty()) { com.lumentv.core.ProfileGate.NEEDS_LIST }
        val keys = withContext(Dispatchers.Default) { ProfileKeys.derive(name, passphrase) }
        val remote = sync.pull(keys)
        Store.saveKeys(Store.StoredKeys(keys.name, keys.id, keys.token, Base64.getEncoder().encodeToString(keys.encKey)))
        profileName = keys.name
        // Joining: what was already on this PC is dated "long ago" so the profile wins on the same item.
        if (remote != null) doc = doc.copy(
            playlists = doc.playlists.mapValues { it.value.copy(at = 1) },
            favorites = doc.favorites.mapValues { it.value.copy(at = 1) },
            settings = doc.settings.mapValues { it.value.copy(at = 1) },
            ratings = doc.ratings.mapValues { it.value.copy(at = 1) },
        )
        syncNow().getOrThrow()
    }

    fun signOut() { Store.saveKeys(null); profileName = null; syncMessage = null; show("Profil déconnecté de ce PC") }

    suspend fun syncNow(): Result<Unit> = syncLock.withLock {
        val k = Store.loadKeys() ?: return Result.success(Unit)
        val keys = ProfileKeys(k.name, k.id, k.token, Base64.getDecoder().decode(k.key))
        syncing = true
        runCatching {
            val before = activeKeyOrFirst
            val r = sync.sync(keys, doc)
            doc = r.doc
            Store.saveDoc(doc)
            lastSync = now(); prefs.putLong("lastSync", lastSync); syncMessage = null
            // A playlist that arrived with the profile is shown when nothing was shown yet.
            if (catalog == null && before == null) activeKeyOrFirst?.let { open(it) }
        }.onFailure { syncMessage = if (it is WrongPassphraseException) it.message else userMessage(it) }
            .also { syncing = false }
    }

    private fun now() = System.currentTimeMillis()
}

/**
 * A programme watched again. Its stream cannot be sought: moving in it asks the server for the stream from another
 * minute ([urlAt], from the programme's start), up to [maxMs] (what has been aired by now).
 */
class CatchupPos(val durationMs: Long, val maxMs: () -> Long, val urlAt: (Long) -> String)

/** What the player plays, and what to save in the history. */
data class PlayItem(
    val kind: String, val uid: String, val title: String, val subtitle: String?, val image: String?, val parentUid: String?,
    val url: String, val live: Boolean, val startMs: Long = 0, val userAgent: String = "",
    /** A live channel: its lower qualities (label, address), to step down to when the picture keeps stopping. */
    val variants: List<Pair<String, String>> = emptyList(),
    /** Episodes: TMDB id of the series, season and episode, to find the recap / intro / credits. */
    val tmdb: String? = null, val season: Int = 0, val episode: Int = 0,
    /** Episodes: the next one, for "Épisode suivant". */
    val next: (() -> PlayItem?)? = null,
    /** A programme watched again: left out of the history, where the channel stays live. */
    val catchup: CatchupPos? = null,
    /** Where in the programme this stream starts. */
    val catchupOffset: Long = 0,
)
