package com.lumentv.phone

import com.lumentv.AppCore
import com.lumentv.core.nowMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * The periodic reload, on every Android device and the iPhone: the list once its period has passed (its
 * "Rechargement auto", every 24 h by default), the guide when it is older than [GUIDE_MS] or was cleared. Asked at
 * start, when the app comes back to the foreground, every half hour while it stays open, and by RefreshWorker on
 * Android. The guide has its own date: a list reloaded alone, or a guide that failed, does not leave it out of date.
 */
object AutoRefresh {
    private const val HOUR = 3_600_000L
    private const val GUIDE_MS = 12 * HOUR
    private val running = Mutex()
    private var started = false

    fun start(core: AppCore) {
        if (started) return
        started = true
        core.scope.launch { while (true) { check(core); delay(30 * 60_000L) } }
        // A crash of an earlier session, sent to the sync server where the developer reads it (CrashReports).
        core.scope.launch(com.lumentv.core.IoDispatcher) {
            delay(5_000)
            com.lumentv.core.CrashReports.send(com.lumentv.core.CrashReports.fileIn(core.platform.filesDir), core.platform.http, core.platform.syncUrl)
        }
        // The search ready before the viewer opens it, once the first screen is drawn.
        core.scope.launch { delay(10_000); prewarm(core) }
    }

    /** The search of the list in use readied in the background (done again only after a reload). */
    private suspend fun prewarm(core: AppCore) {
        val lists = core.playlists.all()
        val pid = core.settings.current().activePlaylistId.takeIf { id -> lists.any { it.id == id } } ?: lists.firstOrNull()?.id ?: return
        core.library.prewarmSearch(pid, core.epg, core.index)
    }

    fun checkNow(core: AppCore) { core.scope.launch { check(core) } }

    suspend fun check(core: AppCore) {
        if (!running.tryLock()) return
        try {
            val now = nowMillis()
            for (p in core.playlists.all()) {
                // A list never read is the first-load screen's job.
                if (p.lastRefresh == 0L || p.id in core.playlists.busy.value) continue
                val listDue = p.refreshHours > 0 && now - p.lastRefresh > p.refreshHours * HOUR
                if (listDue) {
                    if (core.playlists.refresh(p.id).ok && core.epg.epgUrl(p) != null) core.epg.refresh(p.id)
                } else if (core.epg.epgUrl(p) != null && now - p.lastEpgRefresh > GUIDE_MS) {
                    core.epg.refresh(p.id)
                }
            }
            core.epg.prune()
        } finally {
            running.unlock()
        }
        prewarm(core)
    }
}
