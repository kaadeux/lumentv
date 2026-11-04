@file:OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)

package com.lumentv.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.AppGraph
import com.lumentv.data.db.ChannelRow
import com.lumentv.player.PlayerController
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.launch

/** Asks for the notification permission once it is needed (Android 13+), for reminders and the transfer notice. */
fun askNotifications(activity: Activity, launcher: ActivityResultLauncher<String>) {
    if (Build.VERSION.SDK_INT < 33) return
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
    runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

/**
 * The app's own picture-in-picture for TVs without the system one: the channel keeps playing in a corner
 * while the menus are used. It has its own player, released when the app goes to the background.
 */
@Composable
fun MiniPlayerHost(graph: AppGraph) {
    val channelId by graph.miniPlayer.collectAsStateWithLifecycle()
    val id = channelId ?: return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<PlayerController?>(null) }
    val row by produceState<ChannelRow?>(null, id) { value = graph.library.channel(id) }

    DisposableEffect(id, row, lifecycle) {
        fun create() {
            val r = row ?: return
            if (controller != null) return
            val c = PlayerController(context, graph.streamHttp, graph.settings.now())
            controller = c
            com.lumentv.data.xtream.ScreenGuard.started()
            scope.launch { runCatching { c.play(graph.resolver.channel(r.ch)) } }
        }
        fun release() { if (controller != null) com.lumentv.data.xtream.ScreenGuard.stopped(); controller?.release(); controller = null }
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) create()
            if (e == Lifecycle.Event.ON_STOP) release()
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) create()
        onDispose { lifecycle.removeObserver(obs); release() }
    }

    Box(Modifier.fillMaxSize().padding(end = 28.dp, bottom = 28.dp), contentAlignment = Alignment.BottomEnd) {
        Box(Modifier.size(width = 320.dp, height = 180.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
            .border(BorderStroke(2.dp, Palette.Accent), RoundedCornerShape(10.dp))) {
            controller?.let { c ->
                AndroidView(
                    factory = { PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; isFocusable = false } },
                    update = { if (it.player !== c.player) it.player = c.player },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(row?.ch?.name ?: "", color = Color.White, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Scrim)
                    .padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

/** First launch on a device: the family access code (6 digits), asked once. */
@Composable
fun AccessScreen(onUnlocked: () -> Unit, onQuit: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Palette.Background))
    com.lumentv.ui.components.PinDialog(
        "Lumen TV", length = 6, error = error,
        hint = "Application réservée à la famille.\nEntrez le code d'accès à 6 chiffres.",
        onPin = { code -> if (com.lumentv.core.Access.unlock(context, code)) onUnlocked() else error = "Code incorrect" },
        onDismiss = onQuit,
    )
}

/** Looks for a newer version at launch and offers to install it; also used by "Rechercher une mise à jour". */
@Composable
fun UpdateHost(vm: MainViewModel) {
    val updater = vm.graph.updater
    val info by updater.available.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { updater.check() }

    if (progress != null) {
        com.lumentv.ui.components.OptionsDialog("Téléchargement de la mise à jour… ${progress} %", emptyList(), onDismiss = {})
        return
    }
    val i = info ?: return
    com.lumentv.ui.components.OptionsDialog(
        "Mise à jour disponible : Lumen TV ${i.versionName}",
        listOf(
            com.lumentv.ui.components.MenuOption("Installer maintenant") {
                if (!updater.canInstall()) {
                    vm.show("Dans la liste, activez « Lumen TV », appuyez sur Retour, puis choisissez à nouveau « Installer maintenant ».")
                    updater.askInstallPermission(activity)
                    scope.launch { updater.check() }
                } else scope.launch {
                    progress = 0
                    runCatching { updater.download(i) { progress = it } }
                        .onSuccess { f -> progress = null; updater.install(activity, f) }
                        .onFailure { progress = null; vm.show("Mise à jour impossible : ${com.lumentv.core.userMessage(it)}"); updater.available.value = i }
                }
            },
            com.lumentv.ui.components.MenuOption("Plus tard") {},
        ),
        onDismiss = { updater.available.value = null },
        subtitle = i.notes.ifBlank { "Vos listes, favoris et réglages sont conservés." },
    )
}
