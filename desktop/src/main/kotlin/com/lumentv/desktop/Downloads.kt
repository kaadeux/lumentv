package com.lumentv.desktop

import androidx.compose.runtime.mutableStateListOf
import com.lumentv.core.userMessage
import com.lumentv.data.xtream.Presence
import com.lumentv.data.xtream.ScreenGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

object DlStatus { const val QUEUED = "queued"; const val RUNNING = "running"; const val PAUSED = "paused"; const val DONE = "done"; const val FAILED = "failed" }

@Serializable
data class DownloadTask(
    val id: String, val kind: String, val uid: String, val playlist: String,
    val title: String, val subtitle: String? = null, val image: String? = null, val parentUid: String? = null,
    val url: String, val userAgent: String = "", val file: String,
    val total: Long = 0, val done: Long = 0, val status: String = DlStatus.QUEUED, val error: String? = null,
    /** Bytes per second, while running. */
    val speed: Long = 0,
    val tmdb: String? = null, val season: Int = 0, val episode: Int = 0,
)

/**
 * Films and episodes saved to Vidéos\Lumen TV, one at a time (a download uses the subscription's screen too, so
 * it announces itself like a player). Interrupted downloads resume where they stopped.
 */
class Downloads(private val scope: CoroutineScope, http: OkHttpClient, private val presence: Presence, private val listFile: File = File(Store.dir, "downloads.json"),
                val folder: File = File(System.getProperty("user.home"), "Videos/Lumen TV"), private val presenceKeyOf: (String) -> String?) {
    private val http = http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    val tasks = mutableStateListOf<DownloadTask>()
    private var worker: Job? = null

    init {
        folder.mkdirs()
        runCatching { Store.json.decodeFromString<List<DownloadTask>>(listFile.readText()) }.getOrNull()?.let { saved ->
            tasks.addAll(saved.map { if (it.status == DlStatus.RUNNING) it.copy(status = DlStatus.QUEUED, speed = 0) else it })
        }
        kick()
    }

    fun of(uid: String): DownloadTask? = tasks.firstOrNull { it.uid == uid }

    fun add(kind: String, uid: String, playlist: String, title: String, subtitle: String?, image: String?, parentUid: String?, url: String, userAgent: String,
            tmdb: String? = null, season: Int = 0, episode: Int = 0) {
        if (of(uid) != null) return
        val ext = url.substringBefore('?').substringAfterLast('.', "mp4").takeIf { it.length in 2..4 } ?: "mp4"
        val name = listOfNotNull(title, subtitle?.substringBefore(" · ")).joinToString(" ").replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
        var f = File(folder, "$name.$ext"); var n = 2
        while (f.exists() || tasks.any { it.file == f.path }) { f = File(folder, "$name ($n).$ext"); n++ }
        tasks += DownloadTask(System.nanoTime().toString(), kind, uid, playlist, title, subtitle, image, parentUid, url, userAgent, f.path,
            tmdb = tmdb, season = season, episode = episode)
        save(); kick()
    }

    fun pause(id: String) = update(id) { it.copy(status = DlStatus.PAUSED, speed = 0) }.also { if (tasks.none { it.status == DlStatus.RUNNING }) worker?.cancel() }
    fun resume(id: String) { update(id) { it.copy(status = DlStatus.QUEUED, error = null) }; kick() }

    fun remove(id: String) {
        val t = tasks.firstOrNull { it.id == id } ?: return
        if (t.status == DlStatus.RUNNING) worker?.cancel()
        tasks.remove(t); File(t.file).delete(); save(); kick()
    }

    private fun update(id: String, f: (DownloadTask) -> DownloadTask) {
        val i = tasks.indexOfFirst { it.id == id }
        if (i >= 0) tasks[i] = f(tasks[i])
        save()
    }

    private fun save() = runCatching { listFile.writeText(Store.json.encodeToString(tasks.toList())) }

    private fun kick() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (isActive) {
                val t = tasks.firstOrNull { it.status == DlStatus.QUEUED } ?: break
                update(t.id) { it.copy(status = DlStatus.RUNNING, error = null) }
                val result = runCatching { withContext(Dispatchers.IO) { download(t) } }
                // Removed meanwhile: Windows refuses to delete a file still open, so it goes now that it is closed.
                val cur = tasks.firstOrNull { it.id == t.id } ?: run { File(t.file).delete(); null } ?: continue
                if (cur.status != DlStatus.RUNNING) continue // paused or removed meanwhile
                result.onSuccess { update(t.id) { it.copy(status = DlStatus.DONE, speed = 0) } }
                    .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) update(t.id) { it.copy(status = DlStatus.FAILED, error = userMessage(e), speed = 0) } }
            }
        }
    }

    private suspend fun download(t: DownloadTask) {
        val f = File(t.file)
        var have = if (f.exists()) f.length() else 0L
        val req = Request.Builder().url(t.url).header("User-Agent", t.userAgent.ifBlank { "Lumen TV" })
            .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val key = presenceKeyOf(t.playlist)
        // A download takes a screen at the provider like a stream: the screen check before playing knows it is this PC's.
        ScreenGuard.started()
        try { http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw com.lumentv.core.HttpStatusException(r.code)
            if (r.code != 206) have = 0 // the server restarted from the beginning
            val total = (r.body!!.contentLength().takeIf { it > 0 } ?: 0) + have
            RandomAccessFile(f, "rw").use { out ->
                out.setLength(have); out.seek(have)
                val input = r.body!!.byteStream()
                val buf = ByteArray(256 * 1024)
                var done = have; var lastUi = 0L; var lastBytes = done; var lastBeat = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); done += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 700) {
                        val speed = (done - lastBytes) * 1000 / (now - lastUi).coerceAtLeast(1)
                        lastUi = now; lastBytes = done
                        val d = done
                        withContext(Dispatchers.Main) {
                            val cur = tasks.firstOrNull { it.id == t.id }
                            if (cur == null || cur.status != DlStatus.RUNNING) throw kotlinx.coroutines.CancellationException("stopped")
                            tasks[tasks.indexOf(cur)] = cur.copy(done = d, total = total, speed = speed)
                        }
                    }
                    if (key != null && now - lastBeat > Presence.BEAT_MS) { lastBeat = now; presence.beat(key) }
                }
                withContext(Dispatchers.Main) { update(t.id) { it.copy(done = done, total = if (total > 0) total else done) } }
            }
        } } finally { ScreenGuard.stopped() }
        key?.let { presence.leave(it) }
    }

    companion object {
        fun size(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f Go".format(b / (1024.0 * 1024 * 1024))
            b >= 1L shl 20 -> "%.0f Mo".format(b / (1024.0 * 1024))
            else -> "%.0f Ko".format(b / 1024.0)
        }
    }
}
