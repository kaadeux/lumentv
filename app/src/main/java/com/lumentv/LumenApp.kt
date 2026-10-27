package com.lumentv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lumentv.data.backup.BackupManager
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.repo.EpgRepository
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.PlaylistRepository
import com.lumentv.data.repo.StreamResolver
import com.lumentv.data.settings.SettingsStore
import com.lumentv.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph: one instance of each for the whole app. */
class AppGraph(app: Application) {
    val appContext: Application = app
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val db = AppDatabase.create(app)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Shorter read timeout for live streams, so a dead server is noticed quickly and retried. */
    val streamHttp: OkHttpClient = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    val playlists = PlaylistRepository(db, http) { settings.current().defaultUserAgent }
    val epg = EpgRepository(db, playlists) { settings.current().epgDays }
    val library = LibraryRepository(db)
    val resolver = StreamResolver(playlists)
    val backup = BackupManager(app, db, settings)
    val updater = com.lumentv.update.Updater(app, http)
    val transfers = com.lumentv.transfer.TransferRepository(app, db)
    /** Channel shown in the in-app mini-player, null when it is closed. */
    val miniPlayer = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val sports = com.lumentv.data.sports.SportsRepository(db, settings, epg, library)
}

class LumenApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.appScope.launch {
            val auto = graph.playlists.all().any { it.refreshHours > 0 }
            RefreshWorker.sync(this@LumenApp, auto)
            graph.transfers.resumePending()
            val s = graph.settings.current()
            (s.activePlaylistId.takeIf { it > 0 } ?: graph.playlists.all().firstOrNull()?.id)?.let { graph.sports.prewarm(it) }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { graph.http }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(120L * 1024 * 1024).build() }
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}
