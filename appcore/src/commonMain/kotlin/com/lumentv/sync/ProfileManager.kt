package com.lumentv.sync

import com.lumentv.core.Http
import com.lumentv.core.IoDispatcher
import com.lumentv.core.KeyValues
import com.lumentv.core.SecretBox
import com.lumentv.core.TextFile
import com.lumentv.core.logWarn
import com.lumentv.core.nowMillis
import com.lumentv.core.userMessage
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PrefEntity
import com.lumentv.data.db.RatingEntity
import com.lumentv.data.repo.EpgRepository
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.PlaylistRepository
import com.lumentv.data.settings.Keys
import com.lumentv.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ProfileState(val name: String? = null, val lastSync: Long = 0, val syncing: Boolean = false, val error: String? = null)

/**
 * The online profile on this TV: keeps playlists, favourites, history and shared settings in step with the other
 * devices. Everything is encrypted on the device before it is sent ([ProfileCrypto]); the keys are kept with
 * [SecretBox] (Android Keystore, iPhone Keychain). [prefs] keeps the keys and the last sync time, [snapshotFile] the
 * last merged profile (encrypted); [syncUrl] is the sync server, empty when this build has none. The app calls
 * [syncNow] when it comes to the foreground and goes to the background (followAppLifecycle on Android).
 */
@OptIn(ExperimentalEncodingApi::class)
class ProfileManager(
    private val prefs: KeyValues,
    private val snapshotFile: TextFile,
    private val syncUrl: String,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val playlists: PlaylistRepository,
    private val epg: EpgRepository,
    private val library: LibraryRepository,
    http: Http,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val sync = ProfileSync(http, syncUrl)
    private val lock = Mutex()

    private val _state = MutableStateFlow(ProfileState(name = storedKeys()?.name, lastSync = prefs.long("last", 0)))
    val state: StateFlow<ProfileState> = _state

    val enabled get() = syncUrl.isNotEmpty()

    @Serializable private class StoredKeys(val name: String, val id: String, val token: String, val key: String)

    /** Opens (or creates, when the name is new) the profile, then merges this TV's data into it. */
    suspend fun signIn(name: String, passphrase: String): Result<Unit> = runCatching {
        check(db.playlists().all().isNotEmpty()) { com.lumentv.core.ProfileGate.NEEDS_LIST }
        val keys = withContext(Dispatchers.Default) { ProfileKeys.derive(name, passphrase) }
        sync.pull(keys) // checks the passphrase before anything is stored
        prefs.put("keys", SecretBox.encrypt(json.encodeToString(
            StoredKeys(keys.name, keys.id, keys.token, Base64.encode(keys.encKey)))))
        snapshotFile.delete()
        _state.value = ProfileState(name = keys.name)
        syncNow().getOrThrow()
    }

    /** Stops syncing on this TV. Its data stays here; the online profile is untouched. */
    fun signOut() {
        prefs.clear()
        snapshotFile.delete()
        _state.value = ProfileState()
    }

    suspend fun syncNow(): Result<Unit> = lock.withLock {
        val keys = storedKeys() ?: return Result.success(Unit)
        _state.value = _state.value.copy(syncing = true, error = null)
        runCatching {
            withContext(IoDispatcher) {
                val synced = loadSnapshot()
                val current = currentDoc(synced)
                // First sync on this TV: what was already here is dated "long ago", so the profile wins where both
                // have the same item; history keeps its own date.
                val local = if (synced == null) current.copy(
                    playlists = current.playlists.mapValues { it.value.copy(at = 1) },
                    favorites = current.favorites.mapValues { it.value.copy(at = 1) },
                    history = current.history.mapValues { it.value.copy(at = it.value.watchedAt) },
                    ratings = current.ratings.mapValues { it.value.copy(at = it.value.ratedAt) },
                    settings = current.settings.mapValues { it.value.copy(at = 1) },
                ) else ProfileMerge.stampLocal(synced, current)
                val result = sync.sync(keys, local)
                apply(result.doc)
                saveSnapshot(result.doc)
            }
            val now = nowMillis()
            prefs.put("last", now)
            _state.value = _state.value.copy(lastSync = now, syncing = false)
        }.onFailure {
            logWarn(TAG, "sync failed: ${it::class.simpleName}")
            _state.value = _state.value.copy(syncing = false, error = if (it is WrongPassphraseException) it.message else userMessage(it))
        }
    }

    /** Syncs shortly after a change and every 15 minutes (the app adds: at foreground and background). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!enabled) return
        scope.launch {
            combine(library.changes, db.history().observeFingerprint(), db.playlists().observeAll(), settings.flow, db.ratings().observeAll()) { a, b, c, d, e -> listOf(a, b, c.map { it.id to it.name }, d, e) }
                .drop(1).debounce(20_000).collect { syncNow() }
        }
        scope.launch { while (true) { delay(15 * 60_000L); syncNow() } }
    }

    private fun storedKeys(): ProfileKeys? = prefs.string("keys")?.let { s ->
        runCatching {
            val k = json.decodeFromString<StoredKeys>(SecretBox.decrypt(s))
            ProfileKeys(k.name, k.id, k.token, Base64.decode(k.key))
        }.getOrNull()
    }

    private fun loadSnapshot(): ProfileDoc? = snapshotFile.read()
        ?.let { runCatching { json.decodeFromString<ProfileDoc>(SecretBox.decrypt(it)) }.getOrNull() }

    private fun saveSnapshot(d: ProfileDoc) { snapshotFile.write(SecretBox.encrypt(json.encodeToString(d))) }

    private fun keyOf(p: PlaylistEntity) = ProfileMerge.playlistKey(p.type, SecretBox.decrypt(p.sourceEnc), p.username)

    /** This TV's state as a profile, every `at` = 0. Items of playlists this TV does not have are carried over. */
    private suspend fun currentDoc(synced: ProfileDoc?): ProfileDoc {
        val pls = db.playlists().all()
        val keys = pls.associate { it.id to keyOf(it) }
        val here = keys.values.toSet()
        val favorites = db.prefs().everything().filter { it.favorite && it.playlistId in keys }.associate {
            val pk = keys.getValue(it.playlistId)
            ProfileMerge.itemKey(pk, it.kind, it.uid) to SyncFavorite(pk, it.kind, it.uid, it.favOrder)
        } + synced?.favorites.orEmpty().filterValues { it.playlist !in here }
        val history = db.history().everything().filter { it.kind != HistoryKind.FILE && it.playlistId in keys }.associate {
            val pk = keys.getValue(it.playlistId)
            ProfileMerge.itemKey(pk, it.kind, it.uid) to SyncHistory(pk, it.kind, it.uid, it.title, it.subtitle, it.image,
                it.parentUid, it.watchedAt, it.positionMs, it.durationMs)
        } + synced?.history.orEmpty().filterValues { it.playlist !in here }
        val ratings = db.ratings().all().filter { it.playlistId in keys }.associate {
            val pk = keys.getValue(it.playlistId)
            ProfileMerge.itemKey(pk, it.kind, it.uid) to SyncRating(pk, it.kind, it.uid, it.stars, it.ratedAt, it.title, it.image)
        } + synced?.ratings.orEmpty().filterValues { it.playlist !in here }
        return ProfileDoc(
            playlists = pls.associate { p ->
                keys.getValue(p.id) to SyncPlaylist(p.name, p.type, SecretBox.decrypt(p.sourceEnc), p.username, SecretBox.decrypt(p.passwordEnc),
                    SecretBox.decrypt(p.epgUrlEnc), p.epgOffsetMinutes, p.userAgent, p.liveFormat, p.refreshHours, p.dedupe)
            },
            favorites = favorites,
            history = history,
            settings = settings.syncValues().mapValues { SyncValue(it.value) },
            ratings = ratings,
        )
    }

    /** Brings this TV in line with the merged profile. */
    private suspend fun apply(d: ProfileDoc) {
        var changed = false
        val newIds = ArrayList<Long>()
        val local = db.playlists().all().associateBy { keyOf(it) }
        for ((k, sp) in d.playlists) {
            val p = local[k]
            if (sp.deleted) { if (p != null) { playlists.delete(p.id); changed = true }; continue }
            val wanted = (p ?: PlaylistEntity(name = sp.name, type = sp.type, sourceEnc = "")).copy(
                name = sp.name, type = sp.type, sourceEnc = SecretBox.encrypt(sp.source), username = sp.username,
                passwordEnc = SecretBox.encrypt(sp.password), epgUrlEnc = SecretBox.encrypt(sp.epgUrl), epgOffsetMinutes = sp.epgOffsetMinutes,
                userAgent = sp.userAgent, liveFormat = sp.liveFormat, refreshHours = sp.refreshHours, dedupe = sp.dedupe,
            )
            if (p == null) { newIds += playlists.save(wanted); changed = true }
            else if (p.name != sp.name || SecretBox.decrypt(p.passwordEnc) != sp.password || SecretBox.decrypt(p.epgUrlEnc) != sp.epgUrl ||
                p.epgOffsetMinutes != sp.epgOffsetMinutes || p.userAgent != sp.userAgent || p.liveFormat != sp.liveFormat ||
                p.refreshHours != sp.refreshHours || p.dedupe != sp.dedupe) { playlists.save(wanted); changed = true }
        }
        val ids = db.playlists().all().associate { keyOf(it) to it.id }
        for (f in d.favorites.values) {
            val pid = ids[f.playlist] ?: continue
            val cur = db.prefs().get(pid, f.kind, f.uid)
            if ((cur?.favorite ?: false) == f.on) continue
            db.prefs().upsert((cur ?: PrefEntity(pid, f.kind, f.uid)).copy(favorite = f.on, favOrder = if (f.on) f.order else 0))
            changed = true
        }
        for (h in d.history.values) {
            val pid = ids[h.playlist] ?: continue
            val key = LibraryRepository.key(h.kind, pid, h.uid)
            val cur = db.history().get(key)
            if (h.deleted) { if (cur != null) { db.history().delete(key); changed = true }; continue }
            val row = HistoryEntity(key, h.kind, pid, h.uid, h.title, h.subtitle, h.image, h.parentUid, h.watchedAt, h.positionMs, h.durationMs)
            if (cur != row) { db.history().upsert(row); changed = true }
        }
        val rated = db.ratings().all().associateBy { it.key }
        for (r in d.ratings.values) {
            val pid = ids[r.playlist] ?: continue
            val key = LibraryRepository.key(r.kind, pid, r.uid)
            val cur = rated[key]
            if (r.deleted) { if (cur != null) { db.ratings().delete(key); changed = true }; continue }
            val row = RatingEntity(key, r.kind, pid, r.uid, r.stars, r.ratedAt, r.title, r.image)
            if (cur != row) { db.ratings().upsert(row); changed = true }
        }
        settings.applySync(d.settings.mapValues { it.value.value })
        if (changed) library.bump()
        if (newIds.isNotEmpty()) {
            if (settings.current().activePlaylistId == 0L) settings.set(Keys.activePlaylist, newIds.first())
            scope.launch { newIds.forEach { id -> if (playlists.refresh(id).ok) epg.refresh(id) } }
        }
    }

    private companion object { const val TAG = "ProfileManager" }
}
