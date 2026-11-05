package com.lumentv.transfer

import android.content.Context
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.MovieEntity
import com.lumentv.data.db.TransferEntity
import com.lumentv.data.db.TransferKind
import com.lumentv.data.db.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** Downloads of movies and episodes: what was asked for, its progress, and where the file is. */
class TransferRepository(private val context: Context, private val db: AppDatabase) {

    private val _permissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when a download starts: its progress notification needs the permission on Android 13+. */
    val permissionRequests: SharedFlow<Unit> = _permissionRequests

    /** Downloads only: recording rows from an earlier version are left out. */
    fun observe() = db.transfers().observeAll().map { list -> list.filter { it.kind == TransferKind.DOWNLOAD } }

    suspend fun downloadMovie(m: MovieEntity): Long = download(m.playlistId, "mv", m.uid, null, m.name, m.year)
    suspend fun downloadEpisode(e: EpisodeEntity, seriesName: String): Long =
        download(e.playlistId, "ep", e.uid, e.seriesUid, seriesName, "S${e.season} E${e.episode} · ${e.title}")

    private suspend fun download(pid: Long, kind: String, uid: String, seriesUid: String?, title: String, subtitle: String?): Long = withContext(Dispatchers.IO) {
        db.transfers().downloaded(pid, uid)?.let { return@withContext it.id }
        val id = db.transfers().insert(TransferEntity(
            kind = TransferKind.DOWNLOAD, title = title, subtitle = subtitle, playlistId = pid, refKind = kind, refUid = uid,
            seriesUid = seriesUid, startAt = System.currentTimeMillis(),
        ))
        _permissionRequests.tryEmit(Unit)
        TransferService.start(context, id)
        id
    }

    /** A download is starting from the shared screens: its notification needs the permission on Android 13+. */
    fun askNotifications() { _permissionRequests.tryEmit(Unit) }

    suspend fun get(id: Long) = withContext(Dispatchers.IO) { db.transfers().get(id) }

    suspend fun cancel(id: Long) = withContext(Dispatchers.IO) {
        val t = db.transfers().get(id) ?: return@withContext
        if (t.status == TransferStatus.RUNNING) TransferService.cancel(context, id)
        else if (t.status == TransferStatus.SCHEDULED) db.transfers().update(t.copy(status = TransferStatus.CANCELLED))
    }

    /** Starts again where the file stopped (HTTP Range). */
    suspend fun retry(id: Long) = withContext(Dispatchers.IO) {
        val t = db.transfers().get(id) ?: return@withContext
        db.transfers().update(t.copy(status = TransferStatus.SCHEDULED, error = null))
        TransferService.start(context, id)
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        cancel(id)
        db.transfers().get(id)?.filePath?.let { File(it).delete() }
        db.transfers().delete(id)
    }

    /** A download dies with the process: resume the unfinished ones; drop recordings left by an older version. */
    suspend fun resumePending() = withContext(Dispatchers.IO) {
        for (t in db.transfers().pending()) {
            if (t.kind == TransferKind.DOWNLOAD) TransferService.start(context, t.id)
            else db.transfers().update(t.copy(status = TransferStatus.CANCELLED))
        }
    }

    fun folder(): File {
        // Prefer a USB drive when one is plugged in: films fill the TV's own storage quickly.
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        val root = dirs.drop(1).firstOrNull { it.canWrite() && it.freeSpace > 2L * 1024 * 1024 * 1024 } ?: dirs.firstOrNull() ?: context.filesDir
        return File(root, "Telechargements").also { it.mkdirs() }
    }

    fun fileFor(t: TransferEntity, ext: String): File {
        val name = listOfNotNull(t.title, t.subtitle?.substringBefore(" ·")).joinToString(" ")
        val safe = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "lumen" }
        return File(folder(), "$safe.$ext")
    }
}
