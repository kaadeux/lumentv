package com.lumentv.phone

import com.lumentv.AppCore
import com.lumentv.core.nowMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * The periodic reload of the TV and the phone (RefreshWorker on Android): the list once its period has passed (its
 * "Rechargement auto", every 24 h by default), the guide when it is older than [GUIDE_MS] or empty. Asked at start,
 * when the app comes back to the foreground, and every half hour while it stays open.
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
    }

    fun checkNow(core: AppCore) { core.scope.launch { check(core) } }

    private suspend fun check(core: AppCore) {
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
    }
}
