@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.lumentv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.LumenApp
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.screens.EpgFixScreen
import com.lumentv.ui.screens.FavoritesScreen
import com.lumentv.ui.screens.GuideScreen
import com.lumentv.ui.screens.HistoryScreen
import com.lumentv.ui.screens.LiveScreen
import com.lumentv.ui.screens.MovieDetailScreen
import com.lumentv.ui.screens.MoviesScreen
import com.lumentv.ui.screens.PlaylistEditScreen
import com.lumentv.ui.screens.SearchScreen
import com.lumentv.ui.screens.SeriesDetailScreen
import com.lumentv.ui.screens.SeriesScreen
import com.lumentv.ui.screens.SettingsScreen
import com.lumentv.ui.screens.SportsScreen
import com.lumentv.ui.screens.DownloadsScreen
import com.lumentv.ui.components.LumenIcons
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.lumentv.ui.theme.LumenTheme
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.Factory((application as LumenApp).graph) }
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    /** Back in the app: the list and the guide if they are due (a guide left out of date shows nothing). */
    override fun onStart() {
        super.onStart()
        com.lumentv.phone.AutoRefresh.checkNow(vm.graph.core)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val firstLaunch = savedInstanceState == null
        val phone = !Device.isTv(this)
        // Phone: the menus stay upright (sideways they would be cramped); a tablet turns freely. The player goes sideways itself.
        if (phone && resources.configuration.smallestScreenWidthDp < 600) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContent {
            // A poster missing at the provider's (or not loading) is asked of TMDB.
            val posters: suspend (com.lumentv.ui.components.PosterSearch) -> String? = remember { { s ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.graph.logos.info(s.series, s.tmdb, com.lumentv.core.Featured.shortTitle(s.name), s.year)?.poster }
            } }
            LumenTheme { androidx.compose.runtime.CompositionLocalProvider(LocalPhone provides phone, com.lumentv.ui.components.LocalPosterLookup provides posters) {
                var unlocked by remember { mutableStateOf(com.lumentv.core.Access.isUnlocked(this)) }
                if (!unlocked) AccessScreen(onUnlocked = { unlocked = true }, onQuit = { finish() })
                else {
                    run {
                        if (phone) {
                            val w = Device.testWidthDp()
                            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
                                Box(if (w > 0) Modifier.width(w.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                                    val states = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                                    PhoneShell(vm, firstLaunch) { ScreenContent(vm, it, states) }; ToastHost(vm)
                                }
                            }
                        } else AppShell(vm, firstLaunch)
                        if (!phone) MiniPlayerHost(vm.graph)
                        UpdateHost(vm)
                    }
                }
            }
            }
        }
        lifecycleScope.launch {
            vm.graph.transfers.permissionRequests.collect { askNotifications(this@MainActivity, notificationPermission) }
        }
    }
}


@Composable
fun AppShell(vm: MainViewModel, firstLaunch: Boolean) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val contentFocus = remember { FocusRequester() }
    // Each screen keeps its scroll position, search and filters while another one is shown (details, then Back).
    val states = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // A sub-screen (details, playlist form…) takes the focus as soon as it opens, instead of leaving it on the menu.
    var contentHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(vm.current) {
        if (vm.stack.size > 1 || vm.focusContent) {
            vm.focusContent = false
            // The screen may still be loading (nothing focusable yet): try again until the focus is inside it.
            repeat(15) {
                delay(120)
                if (contentHasFocus) return@LaunchedEffect
                runCatching { contentFocus.requestFocus() }
            }
        }
    }

    BackHandler(enabled = vm.stack.size > 1 || vm.root != Screen.Live) { vm.back() }

    // "Open the last channel" at launch.
    LaunchedEffect(firstLaunch, settings.lastChannelId, playlists) {
        if (firstLaunch && playlists != null && settings.startup == "last" && settings.lastChannelId > 0 && !startupDone) {
            startupDone = true
            if (vm.graph.library.channel(settings.lastChannelId) != null) PlayerLauncher.live(context, settings.lastChannelId, null)
        }
    }
    LaunchedEffect(playlists) {
        if (playlists?.isEmpty() == true && vm.root != Screen.Settings) vm.openRoot(Screen.Settings)
    }

    val p = active

    val activity = context as? android.app.Activity
    var miniMenu by remember { mutableStateOf(false) }
    if (miniMenu) {
        val id = vm.graph.miniPlayer.value
        OptionsDialog("Mini-lecteur", listOf(
            MenuOption("Plein écran") { id?.let { PlayerLauncher.live(context, it, null) }; vm.graph.miniPlayer.value = null },
            MenuOption("Fermer le mini-lecteur") { vm.graph.miniPlayer.value = null },
        ), onDismiss = { miniMenu = false })
    }
    val mini by vm.graph.miniPlayer.collectAsStateWithLifecycle()
    // V2: the screens under a bar of tabs at the top, a picture behind the films and series.
    val tabs = buildList {
        if (p == null || p.channelCount > 0) add(TopTab(Screen.Live, "TV"))
        if (p != null && p.channelCount > 0 && p.lastEpgRefresh > 0) add(TopTab(Screen.Guide, "Guide"))
        if (p != null && p.channelCount > 0 && p.lastEpgRefresh > 0) add(TopTab(Screen.Sports, "Sports"))
        if (p != null && p.movieCount > 0) add(TopTab(Screen.Movies, "Films"))
        if (p != null && p.seriesCount > 0) add(TopTab(Screen.Series, "Séries"))
    }
    val icons = buildList {
        if (mini != null) add(TopIcon(null, "Mini-lecteur", Icons.Filled.PlayArrow) { miniMenu = true })
        if (p != null) add(TopIcon(Screen.Search, "Recherche", Icons.Filled.Search))
        if (p != null) add(TopIcon(Screen.Favorites, "Favoris", Icons.Filled.Star))
        if (p != null) add(TopIcon(Screen.History, "Historique", Icons.Filled.Refresh))
        if (p != null) add(TopIcon(Screen.Recordings, "Téléchargés", LumenIcons.Download))
        add(TopIcon(Screen.Settings, "Paramètres", Icons.Filled.Settings))
    }
    val topFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Palette.Background).onKeyEvent { e ->
        // Back after the focused element had its chance (a text field closes its keyboard first).
        if (e.key != Key.Back && e.key != Key.Escape) return@onKeyEvent false
        if (e.type == KeyEventType.KeyUp && !vm.back()) activity?.finish()
        true
    }) {
        BackdropLayer()
        Column(Modifier.fillMaxSize()) {
            TopNav(tabs, icons, vm.root, topFocus) { vm.openTab(it) }
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 40.dp, top = 4.dp, end = 40.dp, bottom = 16.dp)
                .onFocusChanged { contentHasFocus = it.hasFocus }.focusRequester(contentFocus).focusGroup()) {
                ScreenContent(vm, vm.current, states)
            }
        }
    }
    // First launch: the current tab has the focus, as the side menu had.
    LaunchedEffect(Unit) { delay(150); if (!contentHasFocus) runCatching { topFocus.requestFocus() } }
    ToastHost(vm)
}

/** The screen itself, the same on the TV and the phone (each lays itself out for the device). */
@Composable
fun ScreenContent(vm: MainViewModel, screen: Screen, states: androidx.compose.runtime.saveable.SaveableStateHolder) {
    // A tab chosen again: its saved place is forgotten (see MainViewModel.openTab).
    if (vm.freshScreens.remove(screen)) states.removeState(screen.toString())
    ScreenContentIn(vm, screen, states)
}

@Composable
private fun ScreenContentIn(vm: MainViewModel, screen: Screen, states: androidx.compose.runtime.saveable.SaveableStateHolder) =
    states.SaveableStateProvider(screen.toString()) {
            when (val s = screen) {
                Screen.Live -> LiveScreen(vm)
                Screen.Guide -> GuideScreen(vm)
                Screen.Favorites -> FavoritesScreen(vm)
                Screen.Movies -> MoviesScreen(vm)
                Screen.Series -> SeriesScreen(vm)
                Screen.Search -> SearchScreen(vm)
                Screen.History -> HistoryScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Sports -> SportsScreen(vm)
                Screen.Recordings -> DownloadsScreen(vm)
                is Screen.MovieDetail -> MovieDetailScreen(vm, s.playlistId, s.uid)
                is Screen.SeriesDetail -> SeriesDetailScreen(vm, s.playlistId, s.uid)
                is Screen.PlaylistEdit -> PlaylistEditScreen(vm, s.id, s.type)
                is Screen.EpgFix -> EpgFixScreen(vm, s.channelId)
                Screen.More -> Unit
            }
    }

/** Short messages at the bottom of the screen ("Ajouté aux favoris"…). */
@Composable
fun ToastHost(vm: MainViewModel) {
    val toast by vm.toast.collectAsStateWithLifecycle()
    toast?.let { msg ->
        Box(Modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                msg, style = MaterialTheme.typography.titleMedium, color = Palette.Text,
                modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Palette.PanelHigh).padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        LaunchedEffect(msg) { delay(4000); vm.clearToast() }
    }
}

internal var startupDone = false

