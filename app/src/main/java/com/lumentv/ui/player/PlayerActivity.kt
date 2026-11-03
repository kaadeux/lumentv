@file:OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)

package com.lumentv.ui.player

import com.lumentv.ui.components.keepFocus
import androidx.compose.foundation.clickable
import com.lumentv.ui.components.panelWidth

import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.util.Rational
import androidx.lifecycle.lifecycleScope
import com.lumentv.ui.askNotifications
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.LumenApp
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.ProgramEntity
import com.lumentv.player.PlayerController
import com.lumentv.player.SeriesMarkers
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.Logo
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.ProgressLine
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.screens.clock
import com.lumentv.ui.screens.dayLabel
import com.lumentv.ui.screens.progressOf
import com.lumentv.ui.screens.resizeLabel
import com.lumentv.ui.screens.timeRange
import com.lumentv.ui.theme.LumenTheme
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.delay

class PlayerActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels { PlayerViewModel.Factory((application as LumenApp).graph) }
    private var controller by mutableStateOf<PlayerController?>(null)
    private var inPip by mutableStateOf(false)
    private var phone = false
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        phone = !com.lumentv.ui.Device.isTv(this)
        if (phone) {
            // Phone: the video sideways (either way round) over the whole screen; a swipe from the edge shows the bars.
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        if (savedInstanceState == null) vm.load(intent)
        val graph = (application as LumenApp).graph
        setContent {
            LumenTheme {
                androidx.compose.runtime.CompositionLocalProvider(com.lumentv.ui.LocalPhone provides phone) {
                    controller?.let { PlayerScreen(vm, it, inPip, onExit = { finish() }, onMini = { mini() }) } ?: Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
        lifecycleScope.launch {
            graph.transfers.permissionRequests.collect { askNotifications(this@PlayerActivity, notificationPermission) }
        }
    }

    /** System picture-in-picture where the TV offers it, otherwise the app's own mini-player over the menus. */
    private fun mini() {
        val row = vm.channel
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) && android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
                .onFailure { vm.toast = "Image dans l'image refusée par la TV." }
        } else if (vm.mode == Mode.LIVE && row != null) {
            (application as LumenApp).graph.miniPlayer.value = row.ch.id
            startActivity(android.content.Intent(this, com.lumentv.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } else vm.toast = "Le mini-lecteur est disponible pour les chaînes en direct."
    }

    // Phone: leaving for the home screen while a video plays keeps it in a small window, as other video apps do.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (phone && controller?.player?.isPlaying == true && android.os.Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        vm.load(intent)
    }

    // The decoder is created on start and released on stop, so the TV gets it back as soon as the app is left.
    override fun onStart() {
        super.onStart()
        val g = (application as LumenApp).graph
        // Settings decide the buffer and languages of the player, so read them before it is built (a small local file).
        val settings = kotlinx.coroutines.runBlocking { g.settings.current() }
        val c = PlayerController(this, g.streamHttp, settings)
        controller = c
        vm.attach(c)
    }

    override fun onStop() {
        vm.detach()
        controller?.release()
        controller = null
        super.onStop()
    }
}

private enum class Panel { NONE, CHANNELS, OPTIONS, AUDIO, SUBS, RESIZE, SLEEP, EPISODES, NETWORK }

@Composable
private fun PlayerScreen(vm: PlayerViewModel, c: PlayerController, inPip: Boolean, onExit: () -> Unit, onMini: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state by c.state.collectAsStateWithLifecycle()
    val tracksVersion by c.tracksVersion.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var infoUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 5000) }
    var digits by remember { mutableStateOf("") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    val root = remember { FocusRequester() }
    val live = vm.mode == Mode.LIVE
    val phone = com.lumentv.ui.LocalPhone.current
    // The screen follows the video's frame rate (50 Hz for French channels, 23.976 Hz for films): no judder.
    androidx.compose.runtime.LaunchedEffect(state.frameRate) {
        if (!phone && vm.settings.matchFrameRate && state.frameRate > 0f && !inPip) (context as? android.app.Activity)?.let { matchDisplay(it, state.frameRate) }
    }
    val now = System.currentTimeMillis() + tick * 0
    val infoVisible = now < infoUntil || state.error != null || !state.playing && !live || live && state.buffering

    fun showInfo(ms: Long = 5000) { infoUntil = System.currentTimeMillis() + ms }
    fun seek(deltaMs: Long) {
        if (!c.player.isCurrentMediaItemSeekable) return
        val d = c.player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        c.player.seekTo((c.player.currentPosition + deltaMs).coerceIn(0, d))
        showInfo(4000)
    }

    // Clock for progress bars, auto-hide, resume saving, sleep timer.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            delay(500)
            tick++
            position = c.player.currentPosition
            duration = c.player.duration.takeIf { it != C.TIME_UNSET } ?: 0
            if (++n % 20 == 0) {
                vm.saveProgress(c)
                if (live) vm.refreshNowNext()
            }
            if (vm.sleepAt > 0 && System.currentTimeMillis() >= vm.sleepAt) { c.player.pause(); onExit() }
        }
    }
    LaunchedEffect(state.ended) { if (state.ended && !vm.onEnded() && !live) onExit() }

    // Series: "skip recap / intro" at the start, "next episode" as soon as the end credits begin.
    val markers = remember { SeriesMarkers(context) }
    val seriesKey = vm.seriesUid.takeIf { vm.mode == Mode.EPISODE }
    var skipped by remember(vm.subtitle) { mutableStateOf(false) }
    var nextDismissed by remember(vm.subtitle) { mutableStateOf(false) }
    var learnIntroAt by remember { mutableLongStateOf(0L) }
    val introEnd = seriesKey?.let { markers.introEnd(it) } ?: 0L
    var skippedRecap by remember(vm.subtitle) { mutableStateOf(false) }
    val seg = vm.segments?.takeIf { it.known }
    // Known segments (TheIntroDB): two distinct buttons, each only while its part plays (recap, a scene, then the intro).
    val inRecap = seg?.recap?.let { position in it && it.last - position > 3_000 } == true && !skippedRecap
    val inIntro = seg?.intro?.let { position in (it.first - 3_000)..it.last && it.last - position > 3_000 } == true && !skipped
    val showSkip = seriesKey != null && state.playing && state.error == null && (if (seg != null) inRecap || inIntro
        else !skipped && position in 3_000L until (if (introEnd > 0) introEnd - 2_000 else SeriesMarkers.DEFAULT_INTRO_WINDOW))
    val skipLabel = when { seg == null -> "Passer le récap / l'intro"; inRecap -> "Passer le récap"; else -> "Passer l'intro" }
    val credits = seriesKey?.let { markers.creditsLength(it) }?.takeIf { it > 0 } ?: SeriesMarkers.defaultCredits(duration)
    val remaining = duration - position
    val creditsStart = vm.segments?.credits?.first?.takeIf { it in 60_000 until duration }
    val showNext = seriesKey != null && vm.nextEpisode != null && !nextDismissed && duration > 120_000 &&
        (if (creditsStart != null) position >= creditsStart && remaining > 0 else remaining in 1..credits)
    fun skipIntro() {
        if (seg != null) {
            if (inRecap) { skippedRecap = true; c.player.seekTo(seg.recap!!.last + 1) } else if (inIntro) { skipped = true; c.player.seekTo(seg.intro!!.last + 1) }
            return
        }
        skipped = true
        c.player.seekTo(if (introEnd > 0) introEnd else position + SeriesMarkers.DEFAULT_SKIP)
        // Whatever the viewer corrects with Left / Right in the next 15 s becomes this series' intro end.
        learnIntroAt = System.currentTimeMillis() + 15_000
    }
    fun playNext() {
        if (seriesKey != null && remaining > 0) markers.setCreditsLength(seriesKey, remaining)
        vm.nextEpisode?.let { vm.playEpisode(it) }
    }
    LaunchedEffect(tick) {
        if (learnIntroAt in 1..System.currentTimeMillis()) {
            seriesKey?.let { markers.setIntroEnd(it, c.player.currentPosition - 15_000) }
            learnIntroAt = 0
        }
    }
    LaunchedEffect(vm.index, vm.mode) { showInfo() }
    // Automatic quality (live): two stalls within two minutes, or one long stall, play the channel one quality lower;
    // six minutes without a stall go back up.
    var stallTimes by remember(vm.index) { mutableStateOf(emptyList<Long>()) }
    LaunchedEffect(state.stalls) {
        if (!live || vm.mode != Mode.LIVE || !vm.settings.autoQuality || state.stalls == 0) return@LaunchedEffect
        val t = state.lastStallAt
        stallTimes = (stallTimes + t).filter { t - it < com.lumentv.core.ChannelQuality.STALL_WINDOW_MS }
        if (stallTimes.size >= 2 || state.lastStallMs >= com.lumentv.core.ChannelQuality.LONG_STALL_MS) { if (vm.lowerQuality()) stallTimes = emptyList() }
    }
    LaunchedEffect(vm.variant, state.lastStallAt) {
        if (vm.variant == null) return@LaunchedEffect
        delay(com.lumentv.core.ChannelQuality.STEADY_MS)
        vm.raiseQuality()
    }
    // Live behind by a pause or stalls: "Revenir au direct" during the pause and for a while after the delay grew.
    val lagButton = live && vm.mode == Mode.LIVE && state.error == null && (state.pausedLive ||
        state.lagMs >= com.lumentv.core.LiveLag.MIN_MS && tick >= 0 && android.os.SystemClock.elapsedRealtime() - state.lagAt < com.lumentv.core.LiveLag.SHOWN_MS)
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) { delay(1600); digits.toIntOrNull()?.let { vm.goToNumber(it) }; digits = "" }
    }
    LaunchedEffect(panel) { if (panel == Panel.NONE) runCatching { root.requestFocus() } }
    fun back() {
        when {
            panel != Panel.NONE -> panel = Panel.NONE
            showNext -> nextDismissed = true
            digits.isNotEmpty() -> digits = ""
            !phone && infoVisible && state.error == null && (live || state.playing) -> infoUntil = 0
            else -> { vm.saveProgress(c); onExit() }
        }
    }
    BackHandler { back() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(root).focusable()
            // Back is handled here, after the panels: otherwise Compose spends it on moving the focus out of a list.
            .onKeyEvent { e ->
                if (e.key != Key.Back && e.key != Key.Escape) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) back()
                true
            }
            .onPreviewKeyEvent { e ->
                if (panel != Panel.NONE || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.key.nativeKeyCode
                val repeat = e.nativeKeyEvent.repeatCount
                if (code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 && live) {
                    digits = (digits + (code - AndroidKeyEvent.KEYCODE_0)).take(4); return@onPreviewKeyEvent true
                }
                if (live) when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { vm.zap(1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.zap(-1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (state.error != null) c.retryNow() else if (lagButton) c.goLive() else if (infoVisible) panel = Panel.CHANNELS else showInfo(); true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_TV_INPUT -> { panel = Panel.CHANNELS; true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> { panel = Panel.OPTIONS; true }
                    AndroidKeyEvent.KEYCODE_INFO -> { showInfo(8000); true }
                    AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> { vm.previousChannel(); true }
                    AndroidKeyEvent.KEYCODE_CAPTIONS -> { panel = Panel.SUBS; true }
                    // The remote's play / pause key pauses a channel too; "Revenir au direct" then catches up.
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (c.player.playWhenReady) c.player.pause() else c.player.play(); showInfo(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { c.player.pause(); showInfo(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { c.player.play(); true }
                    else -> false
                } else when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        when {
                            state.error != null -> c.retryNow()
                            showNext -> playNext()
                            showSkip -> skipIntro()
                            c.player.isPlaying -> { c.player.pause(); showInfo() }
                            else -> { c.player.play(); showInfo() }
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> {
                        if (state.error != null) c.retryNow() else if (c.player.isPlaying) c.player.pause() else c.player.play(); showInfo(); true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { c.player.play(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { c.player.pause(); showInfo(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-10_000L * (1 + repeat / 4)); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000L * (1 + repeat / 4)); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_INFO -> { showInfo(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> { panel = Panel.OPTIONS; true }
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); true }
                    AndroidKeyEvent.KEYCODE_CAPTIONS -> { panel = Panel.SUBS; true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                }
            },
            update = { v ->
                if (v.player !== c.player) v.player = c.player
                v.resizeMode = when (vm.resizeMode) { 1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; 2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (phone && panel == Panel.NONE) Box(Modifier.fillMaxSize().phoneGestures(c.player.isCurrentMediaItemSeekable && !live,
            onTap = { if (infoVisible && (live || state.playing)) infoUntil = 0 else showInfo() }, onSeek = { seek(it) }))

        vm.fatal?.let { msg ->
            CenterCard(msg, null) { TvButton("Retour", onExit, primary = true) }
            return@Box
        }

        vm.screenWarning?.let { msg ->
            // "Annuler" and Back leave; "Lancer quand même" clears the warning first, so the dismiss does nothing.
            OptionsDialog("Un autre écran regarde déjà", listOf(
                MenuOption("Annuler") { onExit() },
                MenuOption("Lancer quand même") { vm.confirmScreens() },
            ), onDismiss = { if (vm.screenWarning != null) onExit() }, subtitle = msg)
            return@Box
        }

        if (state.error != null) {
            CenterCard(
                state.error!!,
                if (state.retryIn > 0) "Nouvelle tentative dans ${state.retryIn} s (essai ${state.attempt})" else null,
            ) {
                if (phone) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Réessayer", { c.retryNow() }, primary = true)
                    TvButton("Quitter", { vm.saveProgress(c); onExit() })
                } else Text(if (live) "OK : réessayer · Haut / Bas : autre chaîne" else "OK : réessayer · Retour : quitter", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            }
        } else if (state.buffering) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Chargement…", style = MaterialTheme.typography.titleLarge, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Scrim).padding(horizontal = 22.dp, vertical = 10.dp))
            }
        }

        if (digits.isNotEmpty()) {
            Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.TopEnd) {
                Text(digits, style = MaterialTheme.typography.displaySmall, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Scrim).padding(horizontal = 22.dp, vertical = 8.dp))
            }
        }

        if (inPip) return@Box
        if (infoVisible && panel == Panel.NONE) {
            if (phone) PhoneControls(
                title = if (live) vm.channel?.ch?.name ?: vm.title else vm.title,
                subtitle = if (live) vm.channel?.let { "N° ${it.ch.number}" + (state.videoInfo?.let { v -> " · $v" } ?: "") } else vm.subtitle,
                live = live, playing = state.playing, position = position, duration = duration,
                seekable = c.player.isCurrentMediaItemSeekable,
                programme = {
                    val now = vm.nowNext.now
                    if (now != null) {
                        Text(timeRange(now.start, now.stop) + "  " + now.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ProgressLine(progressOf(now.start, now.stop), Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        vm.nowNext.next?.let { n -> Text("Ensuite ${timeRange(n.start, n.stop).substringBefore(" –")} · ${n.title}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1) }
                    } else Text("Pas d'information de programme", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                },
                a = PhoneActions(
                    back = { vm.saveProgress(c); onExit() },
                    playPause = { if (state.error != null) c.retryNow() else if (c.player.isPlaying) c.player.pause() else c.player.play(); showInfo() },
                    seekBy = { seek(it) },
                    seekTo = { c.player.seekTo(it); showInfo(4000) },
                    options = { panel = Panel.OPTIONS },
                    subtitles = { panel = Panel.SUBS },
                    channels = { panel = Panel.CHANNELS },
                    zap = { vm.zap(it); showInfo() }, // ▲ = previous number, ▼ = next, as in the list
                    next = if (vm.mode == Mode.EPISODE && vm.nextEpisode != null) ({ playNext() }) else null,
                    touched = { showInfo(5000) },
                ),
            )
            else if (live) LiveInfoBar(vm, state.videoInfo, if (state.pausedLive) -1 else state.lagMs) else VodBar(vm, position, duration, state.playing)
        }

        if (panel == Panel.NONE && lagButton) {
            Box(Modifier.fillMaxSize().padding(end = if (phone) 20.dp else 48.dp, bottom = if (infoVisible) (if (phone) 110.dp else 150.dp) else (if (phone) 24.dp else 48.dp)),
                contentAlignment = Alignment.BottomEnd) {
                val what = if (state.pausedLive) "En pause" else "${state.lagMs / 1000} s de retard"
                Text("$what · Revenir au direct" + if (phone) "" else "  (OK)", style = MaterialTheme.typography.labelLarge, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Scrim).then(if (phone) Modifier.clickable { c.goLive() } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }

        if (panel == Panel.NONE && (showSkip || showNext)) {
            Box(Modifier.fillMaxSize().padding(end = if (phone) 20.dp else 48.dp, bottom = if (infoVisible) (if (phone) 110.dp else 150.dp) else (if (phone) 24.dp else 48.dp)),
                contentAlignment = Alignment.BottomEnd) {
                if (showNext) {
                    val n = vm.nextEpisode!!
                    Column(Modifier.then(if (phone) Modifier.width(320.dp) else Modifier.panelWidth(380.dp)).clip(RoundedCornerShape(14.dp)).background(Color.White)
                        .then(if (phone) Modifier.clickable { playNext() } else Modifier).padding(18.dp)) {
                        Text("Épisode suivant" + if (vm.settings.autoNext) " dans ${(remaining / 1000).coerceAtLeast(0)} s" else "",
                            style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
                        Text("S${n.season} E${n.episode} · ${n.title}", style = MaterialTheme.typography.titleMedium, color = Palette.OnFocus,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (phone) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvButton("Lancer", { playNext() }, primary = true)
                            TvButton("Finir celui-ci", { nextDismissed = true })
                        } else Text("OK : lancer maintenant · Retour : finir celui-ci", style = MaterialTheme.typography.bodySmall, color = Color(0xFF555B66),
                            modifier = Modifier.padding(top = 6.dp))
                    }
                } else {
                    Text("⏭  $skipLabel" + if (phone) "" else "   (OK)", style = MaterialTheme.typography.titleMedium, color = Palette.OnFocus,
                        modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).then(if (phone) Modifier.clickable { skipIntro() } else Modifier)
                            .padding(horizontal = 22.dp, vertical = 12.dp))
                }
            }
        }

        vm.toast?.let { t ->
            Box(Modifier.fillMaxSize().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
                Text(t, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Scrim).padding(horizontal = 20.dp, vertical = 10.dp))
            }
            LaunchedEffect(t) { delay(2500); vm.toast = null }
        }

        if (panel == Panel.CHANNELS) ChannelsPanel(vm, onDismiss = { panel = Panel.NONE })
    }

    val audio = remember(tracksVersion, panel) { c.audioTracks() }
    val subs = remember(tracksVersion, panel) { c.textTracks() }
    when (panel) {
        Panel.OPTIONS -> OptionsDialog(
            if (live) vm.channel?.ch?.name ?: "Options" else vm.title,
            buildList {
                add(MenuOption("Piste audio : " + (audio.firstOrNull { it.selected }?.label ?: if (audio.isEmpty()) "aucune" else "auto")) { panel = Panel.AUDIO })
                add(MenuOption("Sous-titres : " + (subs.firstOrNull { it.selected }?.label ?: "désactivés")) { panel = Panel.SUBS })
                add(MenuOption("Format d'image : " + resizeLabel(vm.resizeMode)) { panel = Panel.RESIZE })
                add(MenuOption("Minuteur de veille" + if (vm.sleepAt > 0) " : ${((vm.sleepAt - System.currentTimeMillis()) / 60_000) + 1} min" else "") { panel = Panel.SLEEP })
                if (live) {
                    add(MenuOption("Multi-écran") { vm.channel?.let { PlayerLauncher.multiView(context, longArrayOf(it.ch.id)) } })
                    add(MenuOption("Image dans l'image / mini-lecteur") { onMini() })
                    add(MenuOption(if (vm.channel?.isFavorite == true) "Retirer des favoris" else "Ajouter aux favoris") { vm.toggleFavorite() })
                    add(MenuOption("Chaîne précédente") { vm.previousChannel() })
                    add(MenuOption("Liste des chaînes et guide") { panel = Panel.CHANNELS })
                }
                if (vm.mode == Mode.EPISODE) {
                    vm.nextEpisode?.let { n -> add(MenuOption("Épisode suivant : ${n.title.take(40)}") { vm.playEpisode(n) }) }
                    add(MenuOption("Autres épisodes") { panel = Panel.EPISODES })
                }
                if (vm.mode == Mode.LIVE && (vm.channel?.ch?.catchupDays ?: 0) > 0) vm.nowNext.now?.let { pr ->
                    add(MenuOption("Reprendre « ${pr.title.take(40)} » au début") { vm.startOver() })
                }
                if (vm.mode == Mode.CATCHUP) add(MenuOption("Revenir au direct") { vm.backToLive() })
                val reduced = vm.variant
                if (reduced != null) {
                    val label = com.lumentv.core.ChannelQuality.label(reduced.ch.name)
                    add(MenuOption("Qualité réduite ($label) : revenir à la qualité choisie") { vm.chosenQuality() })
                }
                add(MenuOption("État du réseau") { panel = Panel.NETWORK })
                add(MenuOption("Recharger le flux") { c.retryNow() })
            },
            onDismiss = { if (panel == Panel.OPTIONS) panel = Panel.NONE },
            subtitle = state.videoInfo,
        )
        Panel.NETWORK -> com.lumentv.ui.NetworkPanel(vm.graph, c, onDismiss = { if (panel == Panel.NETWORK) panel = Panel.NONE })
        Panel.AUDIO -> OptionsDialog("Piste audio", audio.map { o -> MenuOption(o.label, o.selected) { c.select(C.TRACK_TYPE_AUDIO, o) } }
            .ifEmpty { listOf(MenuOption("Aucune piste audio détectée") {}) }, onDismiss = { if (panel == Panel.AUDIO) panel = Panel.NONE })
        Panel.SUBS -> OptionsDialog("Sous-titres", subs.map { o -> MenuOption(o.label, o.selected) { c.select(C.TRACK_TYPE_TEXT, o) } },
            onDismiss = { if (panel == Panel.SUBS) panel = Panel.NONE }, subtitle = if (subs.size == 1) "Aucun sous-titre dans ce flux." else null)
        Panel.RESIZE -> OptionsDialog("Format d'image", (0..2).map { m -> MenuOption(resizeLabel(m), m == vm.resizeMode) {
            while (vm.resizeMode != m) vm.cycleResize()
        } }, onDismiss = { if (panel == Panel.RESIZE) panel = Panel.NONE })
        Panel.SLEEP -> OptionsDialog("Minuteur de veille", listOf(0, 15, 30, 45, 60, 90, 120).map { m ->
            MenuOption(if (m == 0) "Désactivé" else "$m minutes", m == 0 && vm.sleepAt == 0L) {
                vm.sleepAt = if (m == 0) 0 else System.currentTimeMillis() + m * 60_000L
                vm.toast = if (m == 0) "Minuteur désactivé" else "Arrêt dans $m minutes"
            }
        } + listOfNotNull(vm.nowNext.now?.takeIf { live }?.let { pr ->
            MenuOption("À la fin du programme (${timeRange(pr.start, pr.stop).substringAfter("– ")})") { vm.sleepAt = pr.stop; vm.toast = "Arrêt à la fin du programme" }
        }), onDismiss = { if (panel == Panel.SLEEP) panel = Panel.NONE })
        Panel.EPISODES -> OptionsDialog("Épisodes", vm.episodes.map { e ->
            MenuOption("S${e.season} E${e.episode} · ${e.title}", vm.subtitle?.startsWith("S${e.season} E${e.episode} ") == true) { vm.playEpisode(e) }
        }, onDismiss = { if (panel == Panel.EPISODES) panel = Panel.NONE })
        else -> Unit
    }
}

@Composable
private fun CenterCard(title: String, subtitle: String?, extra: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.panelWidth(520.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Scrim).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            extra()
        }
    }
}

private val bottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))

@Composable
private fun LiveInfoBar(vm: PlayerViewModel, videoInfo: String?, lagMs: Long) {
    val row = vm.channel ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.fillMaxWidth().background(bottomScrim).padding(start = 40.dp, end = 40.dp, top = 60.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.ch.number.toString(), style = MaterialTheme.typography.displaySmall, color = Color.White, modifier = Modifier.padding(end = 18.dp))
            Logo(row.ch.logo, row.ch.name, size = 64.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.ch.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1)
                    if (row.isFavorite) Text("  ★", color = Palette.Gold, style = MaterialTheme.typography.titleMedium)
                    videoInfo?.let { Text("   $it", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
                }
                val now = vm.nowNext.now
                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeRange(now.start, now.stop), style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
                        Text("  " + now.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ProgressLine(progressOf(now.start, now.stop), Modifier.fillMaxWidth(0.7f).padding(vertical = 6.dp))
                    vm.nowNext.next?.let { n ->
                        Text("Ensuite ${timeRange(n.start, n.stop).substringBefore(" –")} · ${n.title}", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
                    }
                    DelayLine(lagMs, vm.variant?.let { com.lumentv.core.ChannelQuality.label(it.ch.name) })
                } else {
                    Text("Pas d'information de programme", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                }
            }
            Text("◀ Liste   ▶ Options   ▲▼ Chaînes", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
    }
}

/**
 * Where the picture stands against live: "● En direct", or "En différé de 0:23" with a bar showing the delay over the
 * last two minutes (from pauses and stalls, ‑1 = paused). The provider's own delay is not counted: it cannot be won back.
 */
@Composable
private fun DelayLine(lagMs: Long, reduced: String?) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val behind = lagMs < 0 || lagMs >= com.lumentv.core.LiveLag.MIN_MS
        Text(if (behind) "●" else "●", color = if (behind) Palette.Gold else Palette.Danger, style = MaterialTheme.typography.labelLarge)
        Text(when {
            lagMs < 0 -> "  En pause"
            behind -> "  En différé de " + clock(lagMs)
            else -> "  En direct"
        }, style = MaterialTheme.typography.labelLarge, color = Color.White)
        if (behind) {
            // Live on the right, the picture where it stands on the bar (two minutes wide at most).
            val f = if (lagMs < 0) 1f else (1f - lagMs / 120_000f).coerceIn(0.05f, 1f)
            Box(Modifier.padding(start = 12.dp).width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Palette.Gold))
            }
            Text("   OK : revenir au direct", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        reduced?.let { Text("   ·   Qualité réduite : $it (connexion lente)", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
    }
}

@Composable
private fun VodBar(vm: PlayerViewModel, position: Long, duration: Long, playing: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().background(bottomScrim).padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 30.dp)) {
            Text(vm.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1)
            vm.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim, maxLines = 1) }
            Spacer(Modifier.height(12.dp))
            ProgressLine(if (duration > 0) position.toFloat() / duration else 0f, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text((if (playing) "▶  " else "❚❚  ") + clock(position), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text("◀ −10 s   ▶ +30 s   ▼ Options", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) "−" + clock(duration - position) + " / " + clock(duration) else "", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}

/** Channel list with a mini-guide of the focused channel; Right moves into its programmes (past ones = catch-up). */
@Composable
private fun ChannelsPanel(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val list = vm.channels
    var focused by remember { mutableStateOf(vm.channel) }
    val start = remember { FocusRequester() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (vm.index - 3).coerceAtLeast(0))
    val epgVersion by vm.graph.epg.version.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF0000000), Color(0xB0000000), Color.Transparent)))) {
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.width(330.dp).fillMaxHeight().padding(start = 24.dp, top = 24.dp, bottom = 24.dp).keepFocus()) {
            itemsIndexed(list, key = { _, r -> r.ch.id }) { i, row ->
                val nn by produceState<com.lumentv.data.repo.NowNext?>(null, row.ch.id, epgVersion) { value = vm.graph.epg.nowNext(row) }
                FocusSurface(
                    onClick = { vm.goTo(i); onDismiss() },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focused = row }.then(if (i == vm.index) Modifier.focusRequester(start) else Modifier),
                    selected = i == vm.index, container = Color(0x66141822), focusedScale = 1.02f,
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.ch.number.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(32.dp))
                        Logo(row.ch.logo, row.ch.name, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(row.ch.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            nn?.now?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
        focused?.let { MiniGuide(vm, it, epgVersion, onDismiss) }
        // Phone: a tap beside the lists closes them.
        if (com.lumentv.ui.LocalPhone.current) Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss))
    }
    LaunchedEffect(Unit) { delay(50); runCatching { start.requestFocus() } }
}

@Composable
private fun MiniGuide(vm: PlayerViewModel, row: ChannelRow, epgVersion: Int, onDismiss: () -> Unit) {
    val programs by produceState(emptyList<ProgramEntity>(), row.ch.id, epgVersion) {
        val now = System.currentTimeMillis()
        val from = now - (if (row.ch.catchupDays > 0) row.ch.catchupDays.coerceAtMost(3) * 86_400_000L else 2 * 3_600_000L)
        value = vm.graph.epg.programs(row, from, now + 24 * 3_600_000L)
    }
    val now = System.currentTimeMillis()
    val firstCurrent = programs.indexOfFirst { it.stop > now }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (firstCurrent - 1).coerceAtLeast(0))
    LaunchedEffect(row.ch.id, programs.size) { if (programs.isNotEmpty()) state.scrollToItem((firstCurrent - 1).coerceAtLeast(0)) }
    Column(Modifier.width(380.dp).fillMaxHeight().padding(start = 16.dp, top = 24.dp, bottom = 24.dp)) {
        Text(row.ch.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
        Text(if (row.ch.catchupDays > 0) "Rattrapage disponible : ${row.ch.catchupDays} jour(s)" else "Programmes", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Spacer(Modifier.height(8.dp))
        if (programs.isEmpty()) Text("Aucune information de programme.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(programs, key = { _, p -> p.id }) { _, pr ->
                val airing = pr.start <= now && pr.stop > now
                val past = pr.stop <= now
                FocusSurface(
                    onClick = {
                        when {
                            airing -> { vm.goTo(vm.channels.indexOf(row)); onDismiss() }
                            past && row.ch.catchupDays > 0 -> { vm.playCatchup(row, pr); onDismiss() }
                            past -> vm.toast = "Pas de rattrapage sur cette chaîne"
                            else -> vm.toast = "Programme à venir"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), selected = airing, container = Color(0x66141822), focusedScale = 1.01f,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text("${dayLabel(pr.start)} ${timeRange(pr.start, pr.stop)}" + if (past && row.ch.catchupDays > 0) "  ⟲ rattrapage" else "",
                            style = MaterialTheme.typography.labelMedium, color = if (airing) Palette.Accent else Color.Unspecified)
                        Text(pr.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (airing) ProgressLine(progressOf(pr.start, pr.stop), Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/**
 * Asks the screen for the refresh rate that shows [fps] evenly (same resolution): 25 or 50 fps → 50 Hz, 23.976 → 23.976 Hz,
 * 29.97 / 59.94 → 59.94 Hz. Nothing when the current rate already fits. The request belongs to the player's window:
 * leaving the player gives the screen back its usual rate.
 */
private fun matchDisplay(activity: android.app.Activity, fps: Float) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
    val display = activity.window.decorView.display ?: return
    val current = display.mode
    // How far a refresh rate is from showing every frame the same number of times (0 = exact).
    fun error(rate: Float): Float {
        val k = kotlin.math.round(rate / fps).coerceAtLeast(1f)
        return if (k > 3f) 1f else kotlin.math.abs(rate - k * fps) / (k * fps)
    }
    if (error(current.refreshRate) < 0.0005f) return android.util.Log.i("Player", "screen ${current.refreshRate} Hz already fits $fps fps").let { }
    val best = display.supportedModes
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && error(it.refreshRate) < 0.002f }
        .minWithOrNull(compareBy<android.view.Display.Mode>({ error(it.refreshRate) }, { -it.refreshRate }))
        ?: return android.util.Log.i("Player", "no screen mode fits $fps fps").let { }
    if (best.modeId == current.modeId) return
    android.util.Log.i("Player", "screen ${current.refreshRate} Hz -> ${best.refreshRate} Hz for $fps fps")
    activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = best.modeId }
}
