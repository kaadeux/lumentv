package com.lumentv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.lumentv.AppGraph
import com.lumentv.phone.FileDownloader
import com.lumentv.phone.LocalDownloads
import com.lumentv.phone.LocalPlatformEntries
import com.lumentv.phone.LumenPhoneApp
import com.lumentv.phone.PlatformEntry
import com.lumentv.phone.PlayRequest
import com.lumentv.phone.VideoPlayer
import com.lumentv.transfer.TransferService
import com.lumentv.ui.player.PlayerLauncher
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * [context] is the activity: PlayerActivity opens over it (Android refuses to start it from the application).
 *
 * The phone's screens shared with the iPhone (:phoneui). The phone keeps its own player (PlayerActivity: ExoPlayer,
 * PiP, history, next episode, skips, quality) and its download service: the shared screens say what to play or keep.
 */
class PhonePlayer(private val context: Context) : VideoPlayer {
    override fun play(request: PlayRequest) {
        val s = request.source ?: return
        val resume = request.startMs > 0
        when (s.kind) {
            "movie" -> PlayerLauncher.movie(context, s.playlistId, s.uid, resume)
            "episode" -> PlayerLauncher.episode(context, s.playlistId, s.seriesUid ?: return, s.uid, resume)
            "live" -> PlayerLauncher.live(context, s.channelId, s.group)
            "catchup" -> PlayerLauncher.catchup(context, s.channelId, com.lumentv.data.db.ProgramEntity(
                playlistId = s.playlistId, epgKey = "", start = s.programStart, stop = s.programStop,
                title = s.programTitle.orEmpty(), titleNorm = "", description = null))
            "file" -> PlayerLauncher.file(context, s.transferId)
        }
    }
}

/** Downloads through the phone's TransferService (foreground, its notification, its folder or a USB drive). */
class PhoneDownloader(private val graph: AppGraph) : FileDownloader {
    override fun start(id: Long, url: String, userAgent: String?, fileName: String) {
        graph.transfers.askNotifications()
        TransferService.start(graph.appContext, id)
    }

    override fun cancel(id: Long) { TransferService.cancel(graph.appContext, id) }

    override fun remove(fileName: String) { if (fileName.isNotBlank()) File(fileName).delete() }
}

/** The classic interface's "Plus" offers the shared one again (and the shared one the classic). */
val LocalSwitchInterface = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun SharedPhoneApp(graph: AppGraph, player: VideoPlayer, toClassic: () -> Unit) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            // As the classic screens' cache: 60 MB at most.
            .diskCache { DiskCache.Builder().directory(File(graph.appContext.cacheDir, "images3").toOkioPath()).maxSizeBytes(60L * 1024 * 1024).build() }
            .build()
    }
    CompositionLocalProvider(
        LocalDownloads provides graph.phoneDownloads,
        LocalPlatformEntries provides listOf(PlatformEntry("◧", "Interface classique",
            "Playlists M3U, plusieurs listes, code PIN, multi-écran, sauvegarde…", toClassic)),
    ) {
        LumenPhoneApp(graph.core, player)
    }
}
