package com.lumentv.phone

import com.lumentv.AppCore
import com.lumentv.core.IoDispatcher
import com.lumentv.core.nowMillis
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.MovieEntity
import com.lumentv.data.db.TransferEntity
import com.lumentv.data.db.TransferKind
import com.lumentv.data.db.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The platform's file downloads: on the iPhone a background URLSession (Swift side, iosApp/), which goes on with the
 * app closed. Files are named, not given a full path: the app's folder changes at each update of the iPhone app.
 */
interface FileDownloader {
    fun start(id: Long, url: String, userAgent: String?, fileName: String)
    fun cancel(id: Long)
    fun remove(fileName: String)
}

/**
 * Films and episodes kept on the device to watch without network, as on the TV and the phone (Téléchargés): the list
 * lives in the shared database (table transfers); the platform downloads and reports back here.
 */
class DownloadManager(private val core: AppCore) {
    var downloader: FileDownloader? = null
    private val db get() = core.db

    fun observe(): Flow<List<TransferEntity>> = db.transfers().observeAll().map { l -> l.filter { it.kind == TransferKind.DOWNLOAD } }

    suspend fun downloadMovie(m: MovieEntity): Long =
        download(m.playlistId, "mv", m.uid, null, m.name, m.year) { core.resolver.movie(m) }

    suspend fun downloadEpisode(e: EpisodeEntity, seriesName: String): Long =
        download(e.playlistId, "ep", e.uid, e.seriesUid, seriesName, "S${e.season} E${e.episode} · ${e.title}") { core.resolver.episode(e) }

    private suspend fun download(pid: Long, kind: String, uid: String, seriesUid: String?, title: String, subtitle: String?,
                                 spec: suspend () -> com.lumentv.data.repo.StreamSpec): Long = withContext(IoDispatcher) {
        db.transfers().downloaded(pid, uid)?.let { return@withContext it.id }
        val id = db.transfers().insert(TransferEntity(kind = TransferKind.DOWNLOAD, title = title, subtitle = subtitle, playlistId = pid,
            refKind = kind, refUid = uid, seriesUid = seriesUid, startAt = nowMillis()))
        val s = spec()
        val t = db.transfers().get(id)!!
        val name = fileName(t, s.url)
        db.transfers().update(t.copy(status = TransferStatus.RUNNING, filePath = name))
        downloader?.start(id, s.url, s.userAgent, name)
        id
    }

    suspend fun cancel(id: Long) = withContext(IoDispatcher) {
        val t = db.transfers().get(id) ?: return@withContext
        downloader?.cancel(id)
        if (t.status == TransferStatus.RUNNING || t.status == TransferStatus.SCHEDULED) db.transfers().update(t.copy(status = TransferStatus.CANCELLED))
    }

    suspend fun retry(id: Long) = withContext(IoDispatcher) {
        val t = db.transfers().get(id) ?: return@withContext
        db.transfers().update(t.copy(status = TransferStatus.RUNNING, error = null, bytes = 0))
        val s = when (t.refKind) {
            "mv" -> core.library.movieByUid(t.playlistId, t.refUid)?.let { core.resolver.movie(it.m) }
            else -> core.library.storedEpisodes(t.playlistId, t.seriesUid ?: "").firstOrNull { it.uid == t.refUid }?.let { core.resolver.episode(it) }
        } ?: return@withContext failed(id, "Le titre n'est plus dans la liste.")
        downloader?.start(id, s.url, s.userAgent, t.filePath ?: fileName(t, s.url))
    }

    suspend fun delete(id: Long) = withContext(IoDispatcher) {
        val t = db.transfers().get(id) ?: return@withContext
        downloader?.cancel(id)
        t.filePath?.let { downloader?.remove(it) }
        db.transfers().delete(id)
    }

    /** The downloaded file of a film or an episode, when there is one. */
    suspend fun fileOf(pid: Long, uid: String): TransferEntity? = withContext(IoDispatcher) { db.transfers().downloaded(pid, uid) }

    // ---- From the platform (Swift calls these) ----------------------------------------------------------------

    fun onProgress(id: Long, bytes: Long, total: Long) { core.scope.launch { db.transfers().progress(id, bytes, total) } }

    fun onDone(id: Long, bytes: Long) {
        core.scope.launch {
            val t = db.transfers().get(id) ?: return@launch
            db.transfers().update(t.copy(status = TransferStatus.DONE, bytes = bytes, totalBytes = maxOf(bytes, t.totalBytes), error = null))
        }
    }

    fun onFailed(id: Long, message: String) { core.scope.launch { failed(id, message) } }

    private suspend fun failed(id: Long, message: String) {
        val t = db.transfers().get(id) ?: return
        if (t.status != TransferStatus.CANCELLED) db.transfers().update(t.copy(status = TransferStatus.FAILED, error = message))
    }

    companion object {
        /** "Dune Part Two 2024.mkv": the title, the episode, the stream's extension; letters and digits only. */
        fun fileName(t: TransferEntity, url: String): String {
            val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "mp4").lowercase().takeIf { it.length in 2..4 } ?: "mp4"
            val base = listOfNotNull(t.title, t.subtitle?.substringBefore(" ·")).joinToString(" ")
            val safe = base.map { c -> if (c.isLetterOrDigit() && c.code < 128 || c == ' ' || c == '-') c else ' ' }.joinToString("")
                .replace(Regex("\\s+"), " ").trim().take(70).ifBlank { "lumen" }
            return "$safe ${t.id}.$ext"
        }

        /** What the player opens for a downloaded file: the platform finds it in its downloads folder. */
        const val SCHEME = "lumen-download:"
    }
}

/** The downloads of the platform, null where the app has none (screens then show no download button). */
val LocalDownloads = androidx.compose.runtime.staticCompositionLocalOf<DownloadManager?> { null }
