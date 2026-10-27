package com.lumentv.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lumentv.LumenApp
import java.util.concurrent.TimeUnit

/** Hourly check: refreshes each playlist whose own interval has elapsed, then its guide. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as LumenApp).graph
        val now = System.currentTimeMillis()
        for (p in g.playlists.all()) {
            if (p.refreshHours <= 0) continue
            if (now - p.lastRefresh >= p.refreshHours * 3_600_000L) {
                val r = g.playlists.refresh(p.id)
                if (r.ok) g.epg.refresh(p.id)
            }
        }
        g.epg.prune()
        return Result.success()
    }

    companion object {
        private const val NAME = "auto-refresh"

        /** Schedules the hourly check when at least one playlist has auto refresh, cancels it otherwise. */
        fun sync(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
