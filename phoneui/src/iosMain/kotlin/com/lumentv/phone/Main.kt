package com.lumentv.phone

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.lumentv.AppCore
import com.lumentv.Platform
import com.lumentv.core.AppleHttp
import com.lumentv.core.KotlinXmlReader
import com.lumentv.core.userDefaults
import com.lumentv.data.db.createAppDatabase
import com.lumentv.data.settings.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/** The app's folders on the iPhone: documents (database, settings, profile) and caches (images, guides). */
private fun folder(kind: ULong): String = (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String)

/** A key of Info.plist, filled when the app is built (TMDB key, sync server); "" when absent. */
private fun plist(key: String): String = (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String).orEmpty()

/** The shared app, built once (database, settings, repositories), as AppGraph does on Android. */
object IosApp {
    val core: AppCore by lazy {
        val docs = folder(NSDocumentDirectory)
        val caches = folder(NSCachesDirectory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val device = userDefaults("device")
        val id = device.string("id") ?: NSUUID().UUIDString.also { device.put("id", it) }
        // Xtream lists of 30 000 titles take time: 2 minutes before giving up.
        val http = AppleHttp(120_000)
        val settings = settingsStore("$docs/settings.preferences_pb").also { it.keepWarm(scope) }
        AppCore(createAppDatabase("$docs/lumen.db"), settings, Platform(
            http = http, tmdbHttp = AppleHttp(15_000), fixtureHttp = AppleHttp(15_000),
            // The shared XML reader reads UTF-8 (what nearly every XMLTV guide is).
            xmlReader = { KotlinXmlReader(it) }, utf8XmlReader = { KotlinXmlReader(it) },
            cacheDir = caches, filesDir = docs, profilePrefs = userDefaults("profile"),
            deviceId = id, deviceName = UIDevice.currentDevice.name,
            tmdbKey = plist("LumenTmdbKey"), syncUrl = plist("LumenSyncUrl"),
        ), scope).also { core -> core.profile.start(); AutoRefresh.start(core) }
    }
    /** Films and episodes kept on the iPhone; the Swift app downloads (background URLSession) and reports here. */
    val downloads: DownloadManager by lazy { DownloadManager(core) }
}

@Composable
private fun Images() = setSingletonImageLoaderFactory { context ->
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory()) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory((folder(NSCachesDirectory) + "/images").toPath()).maxSizeBytes(60L * 1024 * 1024).build() }
        .build()
}

/** Called by the Swift app (iosApp/): the whole app as one view controller; [player] is KSPlayer, Swift side. */
fun MainViewController(player: VideoPlayer, downloader: FileDownloader): UIViewController {
    IosApp.downloads.downloader = downloader
    return ComposeUIViewController {
        Images()
        androidx.compose.runtime.CompositionLocalProvider(LocalDownloads provides IosApp.downloads) {
            LumenPhoneApp(IosApp.core, player)
        }
    }
}

/** For the Swift downloader: where it reports progress, the end or a failure. */
fun downloads(): DownloadManager = IosApp.downloads

/** The Swift app tells the shared app when it comes back to the foreground (profile sync). */
fun appDidBecomeActive() {
    IosApp.core.scope.launch { IosApp.core.profile.syncNow() }
    AutoRefresh.checkNow(IosApp.core)
}
