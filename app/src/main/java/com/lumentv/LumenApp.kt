package com.lumentv

import com.lumentv.core.asHttp
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
import com.lumentv.core.PrivateNet
import com.lumentv.core.PrivateNet.private
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph: one instance of each for the whole app. */
class AppGraph(app: Application) {
    val appContext: Application = app
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app).also { it.keepWarm(appScope) }
    val db = AppDatabase.create(app)

    init { PrivateNet.init(java.io.File(app.filesDir, "https_hosts.txt")) }

    /** Encrypted DNS and HTTPS whenever the server allows it (see PrivateNet): the internet provider sees less. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(com.lumentv.core.NetStats.interceptor)
        .private()
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
    val artwork = com.lumentv.data.repo.Artwork(playlists)
    /** TMDB's requests: with their disk cache and retries (TmdbHttp), shared by the logos. */
    val tmdbHttp = com.lumentv.data.TmdbHttp.client(http, app.cacheDir)
    val tmdb = com.lumentv.data.Tmdb(tmdbHttp.asHttp(), com.lumentv.BuildConfig.TMDB_KEY)
    /** Logos and ratings through the same client, so they share TMDB's disk cache. */
    val logos = com.lumentv.data.TitleLogos(tmdbHttp.asHttp(), com.lumentv.BuildConfig.TMDB_KEY)
    /** The whole library, light, in memory: search and TMDB matching. */
    val index = com.lumentv.data.repo.LibraryIndex(db)
    val discover = com.lumentv.data.repo.Discover(db, tmdb, index)
    val backup = BackupManager(app, db, settings)
    val updater = com.lumentv.update.Updater(app, http)
    val transfers = com.lumentv.transfer.TransferRepository(app, db)
    /** Channel shown in the in-app mini-player, null when it is closed. */
    val miniPlayer = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    /** Which French channel shows each game, days ahead (xmltvfr.fr, sports channels only, twice a day at most). */
    val frenchGuide = com.lumentv.data.sports.FrenchTvGuide(http.asHttp(), com.lumentv.core.TextFile(java.io.File(app.cacheDir, "sports_tv.json").path),
        com.lumentv.core.xmlPullReaders { android.util.Xml.newPullParser() })
    val sports = com.lumentv.data.sports.SportsRepository(db, settings, epg, library, com.lumentv.data.sports.fixtureClient(http), frenchGuide)
    /** Tells the other Lumen TV devices of the same subscription that this one is playing. */
    val presence = com.lumentv.data.xtream.Presence(http.asHttp(), com.lumentv.BuildConfig.SYNC_URL,
        app.getSharedPreferences("device", android.content.Context.MODE_PRIVATE).let { p ->
            p.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
        },
        android.provider.Settings.Global.getString(app.contentResolver, "device_name") ?: android.os.Build.MODEL)
    val introDb = com.lumentv.data.IntroDb(http.asHttp())
    val profile = com.lumentv.sync.ProfileManager(app, db, settings, playlists, epg, library, http, appScope)
}

class LumenApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.profile.start()
        // Request counters, for measuring: "adb shell setprop log.tag.LumenNet DEBUG" writes them every minute.
        graph.appScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                if (android.util.Log.isLoggable("LumenNet", android.util.Log.DEBUG)) android.util.Log.d("LumenNet", com.lumentv.core.NetStats.summary())
            }
        }
        // Caches over their limit (it was lowered in 2.4.9: images 120 → 60 MB, TMDB 40 → 20 MB) are emptied once.
        graph.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { coil.Coil.imageLoader(this@LumenApp).diskCache?.let { if (it.size > it.maxSize) it.clear() } }
            runCatching { com.lumentv.data.TmdbHttp.trimCache(graph.tmdbHttp) }
        }
        graph.appScope.launch {
            val auto = graph.playlists.all().any { it.refreshHours > 0 }
            RefreshWorker.sync(this@LumenApp, auto)
            graph.transfers.resumePending()
            val s = graph.settings.current()
            (s.activePlaylistId.takeIf { it > 0 } ?: graph.playlists.all().firstOrNull()?.id)?.let {
                // Ready before the viewer opens Films or the search: the library's index (titles in memory).
                runCatching { graph.index.of(it) }
                // The sports (a 9 MB guide when older than 12 h) wait for the first screen to be drawn and used.
                graph.appScope.launch { kotlinx.coroutines.delay(8_000); graph.sports.prewarm(it) }
            }
            // Lists saved before v4 lack the series' TMDB ids (recap / intro buttons): reload them once.
            graph.playlists.all().filter { it.type == com.lumentv.data.db.PlaylistType.XTREAM && it.seriesCount > 0 }.forEach { p ->
                if (graph.db.vod().seriesWithTmdb(p.id) == 0) graph.playlists.refresh(p.id)
            }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { graph.http }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(com.lumentv.data.Tmdb.IMAGE_CACHE_BYTES).build() }
        .crossfade(false)
        // Posters and logos in 16-bit colours when they have no transparency: half the memory, no visible change on a TV.
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .build()
}
