@file:OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)

package com.lumentv.ui.player

import com.lumentv.ui.components.panelWidth

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.AppGraph
import com.lumentv.LumenApp
import com.lumentv.core.normalize
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.repo.Groups
import com.lumentv.player.PlayerController
import com.lumentv.ui.components.FilterField
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.Logo
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.theme.LumenTheme
import com.lumentv.ui.components.ignoreHeldOk
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.launch

/** Up to four live channels on screen; only the focused tile plays sound. */
class MultiViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val graph = (application as LumenApp).graph
        val ids = intent.getLongArrayExtra(PlayerLauncher.CHANNEL_IDS) ?: LongArray(0)
        val phone = !com.lumentv.ui.Device.isTv(this)
        if (phone) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContent { LumenTheme { androidx.compose.runtime.CompositionLocalProvider(com.lumentv.ui.LocalPhone provides phone) {
            MultiView(graph, ids.toList(), onExit = { finish() })
        } } }
    }
}

private const val MAX_TILES = 4

@Composable
private fun MultiView(graph: AppGraph, initial: List<Long>, onExit: () -> Unit) {
    val context = LocalContext.current
    val slots = remember { mutableStateListOf<ChannelRow?>() }
    var focused by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        initial.take(MAX_TILES).forEach { id -> slots += graph.library.channel(id) }
        while (slots.size < 2) slots += null
    }

    val cols = 2
    val rows = if (slots.size <= 2) 1 else 2
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(6.dp)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent e.key == Key.Back || e.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_MENU
                when {
                    e.key == Key.Back -> { onExit(); true }
                    e.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_MENU -> { menuFor = focused; true }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        if (i < slots.size) Tile(graph, slots[i], i == focused, i == 0,
                            onFocus = { focused = i }, onClick = { picking = i }, onLong = { menuFor = i })
                    }
                }
            }
        }
    }

    picking?.let { i ->
        ChannelPicker(graph, onPick = { row -> if (i < slots.size) slots[i] = row; picking = null }, onDismiss = { picking = null })
    }
    menuFor?.let { i ->
        val row = slots.getOrNull(i)
        OptionsDialog(row?.ch?.name ?: "Vignette vide", buildList {
            add(MenuOption("Changer de chaîne") { picking = i })
            if (row != null) add(MenuOption("Plein écran") { PlayerLauncher.live(context, row.ch.id, Groups.ALL); onExit() })
            if (slots.size < MAX_TILES) add(MenuOption("Ajouter une vignette (${slots.size + 1}/$MAX_TILES)") { slots += null; picking = slots.lastIndex })
            if (slots.size > 2) add(MenuOption("Retirer cette vignette") { slots.removeAt(i); focused = 0 })
            add(MenuOption("Quitter le multi-écran") { onExit() })
        }, onDismiss = { menuFor = null },
            subtitle = "Le son suit la vignette sélectionnée. Le boîtier décode 4 flux H.264 mais seulement 2 en H.265 (HEVC).")
    }
}

@Composable
private fun Tile(graph: AppGraph, row: ChannelRow?, focused: Boolean, first: Boolean, onFocus: () -> Unit, onClick: () -> Unit, onLong: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<PlayerController?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // One player per tile, created on start and released on stop like the main player.
    DisposableEffect(row?.ch?.id, lifecycle) {
        fun create() {
            if (row == null || controller != null) return
            val settings = kotlinx.coroutines.runBlocking { graph.settings.current() }
            val c = PlayerController(context, graph.streamHttp, settings)
            controller = c
            scope.launch {
                runCatching { c.play(graph.resolver.channel(row.ch)) }.onFailure { error = it.message }
            }
        }
        fun release() { controller?.release(); controller = null }
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) create()
            if (e == Lifecycle.Event.ON_STOP) release()
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) create()
        onDispose { lifecycle.removeObserver(obs); release() }
    }
    LaunchedEffect(focused, controller) { controller?.setVolume(if (focused) 1f else 0f) }
    val state by produceState<com.lumentv.player.PlaybackState?>(null, controller) { controller?.state?.collect { value = it } }

    FocusSurface(
        onClick = onClick, onLongClick = onLong, focusedScale = 1f,
        container = Color.Black, focusedContainer = Color.Black, shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxSize().focusRequester(focus).onFocusChanged { if (it.isFocused) onFocus() }
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Palette.Accent else Color(0x33FFFFFF)), RoundedCornerShape(6.dp)),
    ) {
        val c = controller
        if (c != null) AndroidView(
            factory = { PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; isFocusable = false } },
            update = { if (it.player !== c.player) it.player = c.player },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.BottomStart) {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Scrim).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (row != null) { Logo(row.ch.logo, row.ch.name, size = 28.dp); Text("  " + row.ch.name + if (focused) "  🔊" else "",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                else Text("OK : choisir une chaîne", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val msg = error ?: state?.error
        if (msg != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(msg, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Scrim).padding(10.dp))
        } else if (row != null && state?.buffering == true) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Chargement…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
    LaunchedEffect(Unit) { if (first) runCatching { focus.requestFocus() } }
}

/** Channel list of the active playlist with a filter, to fill a tile. */
@Composable
fun ChannelPicker(graph: AppGraph, onPick: (ChannelRow) -> Unit, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val all by produceState(emptyList<ChannelRow>()) {
        val s = graph.settings.current()
        val pid = s.activePlaylistId.takeIf { it > 0 } ?: graph.playlists.all().firstOrNull()?.id ?: return@produceState
        value = graph.library.channels(pid, Groups.ALL, false, s.sort)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.ignoreHeldOk().panelWidth(560.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(18.dp)) {
            Text("Choisir une chaîne", style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.padding(bottom = 8.dp))
            FilterField(filter, "Nom ou numéro de la chaîne") { filter = it }
            val f = normalize(filter)
            val shown = if (f.isEmpty()) all else all.filter { it.ch.nameNorm.contains(f) || it.ch.number.toString().startsWith(f) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                items(shown.take(500), key = { it.ch.id }) { row ->
                    FocusSurface({ onPick(row) }, Modifier.fillMaxWidth(), container = Palette.PanelHigh, focusedScale = 1.01f) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.ch.number.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
                            Logo(row.ch.logo, row.ch.name, size = 30.dp)
                            Text("  " + row.ch.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
