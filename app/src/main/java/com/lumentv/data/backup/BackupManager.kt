package com.lumentv.data.backup

import android.content.Context
import com.lumentv.core.SecretBox
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PrefEntity
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BackupPlaylist(
    val oldId: Long,
    val name: String,
    val type: String,
    val source: String,
    val username: String,
    val password: String,
    val epgUrl: String,
    val epgOffsetMinutes: Int,
    val userAgent: String,
    val liveFormat: String,
    val refreshHours: Int,
    val dedupe: Boolean,
)

@Serializable
data class BackupPref(val playlistOldId: Long, val kind: String, val uid: String, val favorite: Boolean, val favOrder: Long,
                      val hidden: Boolean, val locked: Boolean, val epgOverride: String?)

/** Watch history and resume positions (format 2). */
@Serializable
data class BackupHistory(val playlistOldId: Long, val kind: String, val uid: String, val title: String, val subtitle: String?,
                         val image: String?, val parentUid: String?, val watchedAt: Long, val positionMs: Long, val durationMs: Long)

@Serializable
data class BackupFile(
    val format: Int = 1,
    val createdAt: Long,
    val includesSecrets: Boolean,
    val settings: Map<String, String>,
    val playlists: List<BackupPlaylist>,
    val prefs: List<BackupPref>,
    val history: List<BackupHistory> = emptyList(),
    /** Learned intro ends and credits lengths per series (SharedPreferences "series_markers"). */
    val seriesMarkers: Map<String, Long> = emptyMap(),
)

/** Exports / imports playlists, favourites, hidden items, EPG corrections, settings and watch history as one JSON file. */
class BackupManager(context: Context, private val db: AppDatabase, private val settings: SettingsStore) {
    private val markers = context.getSharedPreferences("series_markers", Context.MODE_PRIVATE)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun export(target: File, includeSecrets: Boolean): Int = withContext(Dispatchers.IO) {
        val playlists = db.playlists().all()
        val file = BackupFile(
            format = 2,
            createdAt = System.currentTimeMillis(),
            includesSecrets = includeSecrets,
            settings = settings.export(),
            playlists = playlists.map { p ->
                BackupPlaylist(
                    oldId = p.id, name = p.name, type = p.type,
                    source = if (includeSecrets) SecretBox.decrypt(p.sourceEnc) else "",
                    username = if (includeSecrets) p.username else "",
                    password = if (includeSecrets) SecretBox.decrypt(p.passwordEnc) else "",
                    epgUrl = if (includeSecrets) SecretBox.decrypt(p.epgUrlEnc) else "",
                    epgOffsetMinutes = p.epgOffsetMinutes, userAgent = p.userAgent, liveFormat = p.liveFormat,
                    refreshHours = p.refreshHours, dedupe = p.dedupe,
                )
            },
            prefs = db.prefs().everything().map {
                BackupPref(it.playlistId, it.kind, it.uid, it.favorite, it.favOrder, it.hidden, it.locked, it.epgOverride)
            },
            history = db.history().everything().map {
                BackupHistory(it.playlistId, it.kind, it.uid, it.title, it.subtitle, it.image, it.parentUid, it.watchedAt, it.positionMs, it.durationMs)
            },
            seriesMarkers = markers.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap(),
        )
        target.parentFile?.mkdirs()
        target.writeText(json.encodeToString(file))
        playlists.size
    }

    /** @return the ids of the playlists created; they still need a refresh to load their channels. */
    suspend fun import(source: File): List<Long> = withContext(Dispatchers.IO) {
        val file = json.decodeFromString<BackupFile>(source.readText())
        settings.import(file.settings)
        val idMap = HashMap<Long, Long>()
        for (b in file.playlists) {
            if (b.source.isBlank()) continue
            val id = db.playlists().insert(
                PlaylistEntity(
                    name = b.name, type = b.type, sourceEnc = SecretBox.encrypt(b.source), username = b.username,
                    passwordEnc = SecretBox.encrypt(b.password), epgUrlEnc = SecretBox.encrypt(b.epgUrl),
                    epgOffsetMinutes = b.epgOffsetMinutes, userAgent = b.userAgent, liveFormat = b.liveFormat,
                    refreshHours = b.refreshHours, dedupe = b.dedupe,
                )
            )
            idMap[b.oldId] = id
        }
        db.prefs().upsertAll(file.prefs.mapNotNull { p ->
            val pid = idMap[p.playlistOldId] ?: return@mapNotNull null
            PrefEntity(pid, p.kind, p.uid, p.favorite, p.favOrder, p.hidden, p.locked, p.epgOverride)
        })
        file.history.forEach { h ->
            val pid = idMap[h.playlistOldId] ?: return@forEach
            db.history().upsert(HistoryEntity(LibraryRepository.key(h.kind, pid, h.uid), h.kind, pid, h.uid, h.title, h.subtitle, h.image,
                h.parentUid, h.watchedAt, h.positionMs, h.durationMs))
        }
        markers.edit().apply { file.seriesMarkers.forEach { (k, v) -> putLong(k, v) } }.apply()
        idMap.values.toList()
    }
}
