package com.lumentv.desktop

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
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(true).build()
    val library = Library(http)
    /** Official sports calendar (live and upcoming games). */
    val fixtures = com.lumentv.data.sports.FixtureClient(http)
    private val sync = ProfileSync(http, BuildInfo.SYNC_URL)
    private val prefs = Preferences.userRoot().node("lumentv")
    private val syncLock = Mutex()

    /** Tells the other Lumen TV devices of the same subscription that this PC is playing. */
    val presence = com.lumentv.data.xtream.Presence(http, BuildInfo.SYNC_URL,
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
    var loadError by mutableStateOf<String?>(null); private set
    var guide by mutableStateOf<Map<String, List<Prog>>>(emptyMap()); private set
    var toast by mutableStateOf<String?>(null)

    val syncEnabled get() = BuildInfo.SYNC_URL.isNotEmpty()
    val playlists get() = doc.playlists.filterValues { !it.deleted }
    val active: SyncPlaylist? get() = activeKey?.let { playlists[it] } ?: playlists.values.firstOrNull()
    private val activeKeyOrFirst get() = activeKey?.takeIf { it in playlists } ?: playlists.keys.firstOrNull()

    fun setting(key: String, default: String = "") = doc.settings[key]?.value ?: default
    fun setSetting(key: String, value: String) = change { it.copy(settings = it.settings + (key to SyncValue(value, now()))) }

    fun show(msg: String) { toast = msg; scope.launch { delay(3500); if (toast == msg) toast = null } }

    // ---- Playlists ------------------------------------------------------------------------------------------

    fun start() {
        // Loads the VLC engine now, in the background, so the first playback does not wait for it.
        Thread { runCatching { Vlc.factory } }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        activeKeyOrFirst?.let { open(it) }
        if (profileName != null) scope.launch { syncNow() }
        scope.launch { while (true) { delay(10 * 60_000L); if (profileName != null) syncNow() } }
    }

    fun addPlaylist(p: SyncPlaylist) {
        val key = ProfileMerge.playlistKey(p.type, p.source, p.username)
        change { it.copy(playlists = it.playlists + (key to p.copy(at = now()))) }
        open(key, force = true)
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
                runCatching { library.load(p) }
                    .onSuccess { c -> catalog = c; withContext(Dispatchers.IO) { Store.saveCatalog(key, c) } }
                    .onFailure { loadError = userMessage(it) }
            }
            loading = false
            catalog?.let { c -> runCatching { library.guide(c, p.epgOffsetMinutes, p.userAgent) }.onSuccess { guide = it } }
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
        if (System.getenv("LUMEN_NO_HISTORY") != null) return // tests: leave the real history alone
        val k = activeKeyOrFirst ?: return
        val key = ProfileMerge.itemKey(k, item.kind, item.uid)
        val h = SyncHistory(k, item.kind, item.uid, item.title, item.subtitle, item.image, item.parentUid, now(),
            if (item.live) 0 else positionMs, if (item.live) 0 else durationMs, at = now())
        change(sync = false) { it.copy(history = it.history + (key to h)) }
    }

    fun clearHistory() = change { d -> d.copy(history = d.history.mapValues { (_, h) -> if (h.playlist == activeKeyOrFirst) h.copy(deleted = true, at = now()) else h }) }

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
        val keys = withContext(Dispatchers.Default) { ProfileKeys.derive(name, passphrase) }
        val remote = sync.pull(keys)
        Store.saveKeys(Store.StoredKeys(keys.name, keys.id, keys.token, Base64.getEncoder().encodeToString(keys.encKey)))
        profileName = keys.name
        // Joining: what was already on this PC is dated "long ago" so the profile wins on the same item.
        if (remote != null) doc = doc.copy(
            playlists = doc.playlists.mapValues { it.value.copy(at = 1) },
            favorites = doc.favorites.mapValues { it.value.copy(at = 1) },
            settings = doc.settings.mapValues { it.value.copy(at = 1) },
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

/** What the player plays, and what to save in the history. */
data class PlayItem(
    val kind: String, val uid: String, val title: String, val subtitle: String?, val image: String?, val parentUid: String?,
    val url: String, val live: Boolean, val startMs: Long = 0, val userAgent: String = "",
    /** Episodes: TMDB id of the series, season and episode, to find the recap / intro / credits. */
    val tmdb: String? = null, val season: Int = 0, val episode: Int = 0,
    /** Episodes: the next one, for "Épisode suivant". */
    val next: (() -> PlayItem?)? = null,
)
