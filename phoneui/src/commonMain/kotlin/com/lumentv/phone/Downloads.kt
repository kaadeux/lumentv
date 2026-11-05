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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
class DownloadManager(
    private val core: AppCore,
    /** The platform's downloader picks the file's place itself (Android's TransferService, its own folder or a USB drive). */
    private val platformPaths: Boolean = false,
) {
    var downloader: FileDownloader? = null

    init {
        // The queue goes on whatever ends a download (the platform may write the table itself, as on Android).
        core.scope.launch {
            observe().map { l -> l.none { it.status == TransferStatus.RUNNING } && l.any { it.status == TransferStatus.SCHEDULED } }
                .distinctUntilChanged().collect { if (it) next() }
        }
    }
    private val db get() = core.db
    /** One download at a time: the others wait their turn ("En attente"), in the order they were asked. */
    private val queue = kotlinx.coroutines.sync.Mutex()

    fun observe(): Flow<List<TransferEntity>> = db.transfers().observeAll().map { l -> l.filter { it.kind == TransferKind.DOWNLOAD } }

    suspend fun downloadMovie(m: MovieEntity): Long =
        download(m.playlistId, "mv", m.uid, null, m.name, m.year) { core.resolver.movie(m) }

    suspend fun downloadEpisode(e: EpisodeEntity, seriesName: String): Long =
        download(e.playlistId, "ep", e.uid, e.seriesUid, seriesName, "S${e.season} E${e.episode} · ${e.title}") { core.resolver.episode(e) }

    private suspend fun download(pid: Long, kind: String, uid: String, seriesUid: String?, title: String, subtitle: String?,
                                 @Suppress("UNUSED_PARAMETER") spec: suspend () -> com.lumentv.data.repo.StreamSpec): Long = withContext(IoDispatcher) {
        db.transfers().downloaded(pid, uid)?.let { return@withContext it.id }
        // Already asked (waiting or under way): the same line.
        db.transfers().pending().firstOrNull { it.kind == TransferKind.DOWNLOAD && it.playlistId == pid && it.refUid == uid }?.let { return@withContext it.id }
        val id = db.transfers().insert(TransferEntity(kind = TransferKind.DOWNLOAD, title = title, subtitle = subtitle, playlistId = pid,
            refKind = kind, refUid = uid, seriesUid = seriesUid, startAt = nowMillis(), status = TransferStatus.SCHEDULED))
        next()
        id
    }

    /** Starts the oldest waiting download when none is under way. */
    suspend fun next(): Unit = withContext(IoDispatcher) {
        queue.withLock {
            val pending = db.transfers().pending().filter { it.kind == TransferKind.DOWNLOAD }
            if (pending.any { it.status == TransferStatus.RUNNING }) return@withLock
            val t = pending.filter { it.status == TransferStatus.SCHEDULED }.minByOrNull { it.startAt } ?: return@withLock
            val s = specOf(t)
            if (s == null) {
                db.transfers().update(t.copy(status = TransferStatus.FAILED, error = "Le titre n'est plus dans la liste."))
                return@withLock
            }
            val name = t.filePath ?: if (platformPaths) "" else fileName(t, s.url)
            db.transfers().update(t.copy(status = TransferStatus.RUNNING, filePath = name.ifEmpty { t.filePath }, error = null))
            downloader?.start(t.id, s.url, s.userAgent, name)
        }
        // A failed start (title gone): try the one after.
        if (db.transfers().pending().none { it.kind == TransferKind.DOWNLOAD && it.status == TransferStatus.RUNNING } &&
            db.transfers().pending().any { it.kind == TransferKind.DOWNLOAD && it.status == TransferStatus.SCHEDULED }) next()
    }

    private suspend fun specOf(t: TransferEntity): com.lumentv.data.repo.StreamSpec? = when (t.refKind) {
        "mv" -> core.library.movieByUid(t.playlistId, t.refUid)?.let { core.resolver.movie(it.m) }
        else -> core.library.storedEpisodes(t.playlistId, t.seriesUid ?: "").firstOrNull { it.uid == t.refUid }?.let { core.resolver.episode(it) }
    }

    /**
     * At start: the downloads iOS still runs ([activeIds], from the background session) stay "under way"; the others
     * marked so were stopped with the app and wait again. Then the queue goes on.
     */
    fun resume(activeIds: List<Long>) {
        core.scope.launch {
            db.transfers().pending().filter { it.kind == TransferKind.DOWNLOAD && it.status == TransferStatus.RUNNING && it.id !in activeIds }
                .forEach { db.transfers().update(it.copy(status = TransferStatus.SCHEDULED, bytes = 0)) }
            next()
        }
    }

    /** For Swift: the ids as "12,15". */
    fun resumeIds(ids: String) = resume(ids.split(',').mapNotNull { it.trim().toLongOrNull() })

    suspend fun cancel(id: Long) = withContext(IoDispatcher) {
        val t = db.transfers().get(id) ?: return@withContext
        downloader?.cancel(id)
        if (t.status == TransferStatus.RUNNING || t.status == TransferStatus.SCHEDULED) db.transfers().update(t.copy(status = TransferStatus.CANCELLED))
        next()
    }

    /** Back in the queue, last. */
    suspend fun retry(id: Long) = withContext(IoDispatcher) {
        val t = db.transfers().get(id) ?: return@withContext
        db.transfers().update(t.copy(status = TransferStatus.SCHEDULED, error = null, bytes = 0, startAt = nowMillis()))
        next()
    }

    suspend fun delete(id: Long) = withContext(IoDispatcher) {
        val t = db.transfers().get(id) ?: return@withContext
        downloader?.cancel(id)
        t.filePath?.let { downloader?.remove(it) }
        db.transfers().delete(id)
        next()
    }

    /** The downloaded file of a film or an episode, when there is one. */
    suspend fun fileOf(pid: Long, uid: String): TransferEntity? = withContext(IoDispatcher) { db.transfers().downloaded(pid, uid) }

    // ---- From the platform (Swift calls these) ----------------------------------------------------------------

    fun onProgress(id: Long, bytes: Long, total: Long) { core.scope.launch { db.transfers().progress(id, bytes, total) } }

    fun onDone(id: Long, bytes: Long) {
        core.scope.launch {
            val t = db.transfers().get(id) ?: return@launch
            db.transfers().update(t.copy(status = TransferStatus.DONE, bytes = bytes, totalBytes = maxOf(bytes, t.totalBytes), error = null))
            next()
        }
    }

    fun onFailed(id: Long, message: String) { core.scope.launch { failed(id, message); next() } }

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
