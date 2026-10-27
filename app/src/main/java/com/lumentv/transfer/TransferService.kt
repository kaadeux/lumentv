package com.lumentv.transfer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.lumentv.LumenApp
import com.lumentv.core.Redact
import com.lumentv.core.userMessage
import com.lumentv.data.db.TransferEntity
import com.lumentv.data.db.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Runs downloads in a foreground service, so they continue while the app is not on screen. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()
    private val titles = ConcurrentHashMap<Long, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getLongExtra(EXTRA_ID, 0) ?: 0
        when (intent?.action) {
            ACTION_CANCEL -> cancelled += id
            ACTION_START -> if (id > 0 && !jobs.containsKey(id)) jobs[id] = scope.launch { run(id) }
        }
        if (jobs.isEmpty()) stopSelfSoon()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = Notifications.transfers(this, titles.values.toList())
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.TRANSFER_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Notifications.TRANSFER_ID, n)
    }

    private suspend fun run(id: Long) {
        val g = (application as LumenApp).graph
        val dao = g.db.transfers()
        var t: TransferEntity = dao.get(id) ?: return finish(id)
        if (t.status == TransferStatus.CANCELLED || t.status == TransferStatus.DONE) return finish(id)
        titles[id] = t.title
        updateNotification()
        try {
            val spec = when (t.refKind) {
                "mv" -> g.resolver.movie((g.library.movieByUid(t.playlistId, t.refUid) ?: error("Ce film n'existe plus dans la playlist.")).m)
                else -> g.resolver.episode(g.library.storedEpisodes(t.playlistId, t.seriesUid.orEmpty()).firstOrNull { it.uid == t.refUid }
                    ?: error("Cet épisode n'est plus disponible."))
            }
            val file = t.filePath?.let(::File) ?: g.transfers.fileFor(t, extensionFor(spec.url))
            t = t.copy(status = TransferStatus.RUNNING, filePath = file.absolutePath, error = null)
            dao.update(t)
            var lastWrite = 0L
            val copier = StreamCopier(g.http, spec.userAgent, spec.referer, cancelled = { id in cancelled }) { bytes, total ->
                val now = System.currentTimeMillis()
                if (now - lastWrite > 2000) { lastWrite = now; runBlocking { dao.progress(id, bytes, total) } }
            }
            val bytes = copier.download(spec.url, file)
            dao.update(t.copy(status = if (bytes > 0) TransferStatus.DONE else TransferStatus.FAILED, bytes = bytes, totalBytes = bytes,
                error = if (bytes > 0) null else "Aucune donnée reçue du serveur."))
        } catch (e: InterruptedException) {
            dao.get(id)?.let { dao.update(it.copy(status = TransferStatus.CANCELLED)) }
        } catch (e: Throwable) {
            Log.w(TAG, "download $id failed: ${Redact.text(e.toString())}")
            val msg = if (e is IllegalStateException) e.message ?: userMessage(e) else userMessage(e)
            dao.get(id)?.let { dao.update(it.copy(status = TransferStatus.FAILED, error = msg)) }
        }
        finish(id)
    }

    private fun finish(id: Long) {
        jobs.remove(id)
        titles.remove(id)
        cancelled.remove(id)
        if (jobs.isEmpty()) stopSelfSoon() else updateNotification()
    }

    private fun updateNotification() {
        Notifications.manager(this).notify(Notifications.TRANSFER_ID, Notifications.transfers(this, titles.values.toList()))
    }

    private fun stopSelfSoon() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun extensionFor(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            ext == "m3u8" -> "ts"
            ext.length in 2..4 -> ext
            else -> "mp4"
        }
    }

    companion object {
        private const val TAG = "Downloads"
        private const val EXTRA_ID = "id"
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"

        fun start(context: Context, id: Long) = ContextCompat.startForegroundService(
            context, Intent(context, TransferService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id),
        )

        fun cancel(context: Context, id: Long) = ContextCompat.startForegroundService(
            context, Intent(context, TransferService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
        )
    }
}

/** After an app update, unfinished downloads pick up where they stopped. */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { (context.applicationContext as LumenApp).graph.transfers.resumePending() } finally { pending.finish() }
        }
    }
}
