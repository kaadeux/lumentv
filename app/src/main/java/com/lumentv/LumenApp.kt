package com.lumentv

import com.lumentv.core.asHttp
import com.lumentv.core.xmlPullReaders
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lumentv.data.backup.BackupManager
import com.lumentv.work.RefreshWorker
import com.lumentv.sync.startWithAppLifecycle
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
    val settings = com.lumentv.data.settings.settingsStore(app).also { it.keepWarm(appScope) }
    val db = com.lumentv.data.db.createAppDatabase(app)

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

    /** TMDB's requests: with their disk cache and retries (TmdbHttp), shared by the logos. */
    val tmdbHttp = com.lumentv.data.TmdbHttp.client(http, app.cacheDir)

    /** Everything but the screens, shared with the iPhone (AppCore in :appcore). */
    val core = com.lumentv.AppCore(db, settings, com.lumentv.Platform(
        http = http.asHttp(),
        tmdbHttp = tmdbHttp.asHttp(),
        fixtureHttp = http.newBuilder().addInterceptor(com.lumentv.core.RetryInterceptor()).build().asHttp(),
        // XMLTV: Android's pull parser, without namespaces, in the encoding the guide declares.
        xmlReader = xmlPullReaders(encoding = null) {
            android.util.Xml.newPullParser().apply { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        },
        utf8XmlReader = xmlPullReaders { android.util.Xml.newPullParser() },
        cacheDir = app.cacheDir.path,
        filesDir = app.filesDir.path,
        profilePrefs = com.lumentv.core.sharedPrefs(app, "profile"),
        deviceId = app.getSharedPreferences("device", android.content.Context.MODE_PRIVATE).let { p ->
            p.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
        },
        deviceName = android.provider.Settings.Global.getString(app.contentResolver, "device_name") ?: android.os.Build.MODEL,
        tmdbKey = BuildConfig.TMDB_KEY,
        syncUrl = BuildConfig.SYNC_URL,
    ), appScope)
    val playlists = core.playlists
    val epg = core.epg
    val library = core.library
    val resolver = core.resolver
    val artwork = core.artwork
    val tmdb = core.tmdb
    val logos = core.logos
    val index = core.index
    val discover = core.discover
    val frenchGuide = core.frenchGuide
    val sports = core.sports
    val presence = core.presence
    val introDb = core.introDb
    val profile = core.profile
    val backup = BackupManager(app, db, settings)
    val updater = com.lumentv.update.Updater(app, http)
    val transfers = com.lumentv.transfer.TransferRepository(app, db)
    /** Channel shown in the in-app mini-player, null when it is closed. */
    val miniPlayer = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
}

class LumenApp : Application(), ImageLoaderFactory {
    /**
     * A crash is written down before the app closes, and sent at the next start (CrashReports, shared with the iPhone
     * and the PC). The system's own records add what Kotlin cannot catch: a freeze (ANR), a crash in the video
     * decoder, a kill for lack of memory.
     */
    private fun installCrashReports() {
        val app = com.lumentv.core.CrashReports.App(
            if (com.lumentv.ui.Device.isTv(this)) "tv" else "android", BuildConfig.VERSION_NAME,
            android.provider.Settings.Global.getString(contentResolver, "device_name") ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        val file = com.lumentv.core.CrashReports.fileIn(filesDir.path)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            com.lumentv.core.CrashReports.record(file, e, app)
            previous?.uncaughtException(thread, e)
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) Thread {
            runCatching {
                val prefs = getSharedPreferences("crash", MODE_PRIVATE)
                val since = prefs.getLong("exitsSeen", 0)
                val am = getSystemService(android.app.ActivityManager::class.java)
                val exits = am.getHistoricalProcessExitReasons(packageName, 0, 10).filter { it.timestamp > since }
                // The first time: only remember where we are (old exits are not news).
                if (since > 0) exits.filter { it.reason in setOf(android.app.ApplicationExitInfo.REASON_ANR,
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_LOW_MEMORY) }.forEach { x ->
                    val kind = when (x.reason) { android.app.ApplicationExitInfo.REASON_ANR -> "Gel (ANR)"
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Plantage natif"; else -> "Fermée faute de mémoire" }
                    val trace = if (x.reason == android.app.ApplicationExitInfo.REASON_ANR)
                        runCatching { x.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty().take(30_000) else ""
                    com.lumentv.core.CrashReports.recordText(file, "$kind : ${x.description.orEmpty()}",
                        "processus ${x.processName} · mémoire ${x.pss / 1024} Mo\n$trace", app, x.timestamp)
                }
                prefs.edit().putLong("exitsSeen", maxOf(since, exits.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis())).apply()
            }
        }.start()
    }

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashReports()
        graph = AppGraph(this)
        graph.profile.startWithAppLifecycle(graph.appScope)
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
            com.lumentv.phone.AutoRefresh.start(graph.core)
            graph.transfers.resumePending()
            val s = graph.settings.current()
            // The list in use: a list removed since would have its (empty) index built instead of the real one.
            val lists = graph.playlists.all()
            (s.activePlaylistId.takeIf { id -> lists.any { it.id == id } } ?: lists.firstOrNull()?.id)?.let {
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
