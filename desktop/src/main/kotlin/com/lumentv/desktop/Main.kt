package com.lumentv.desktop

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

enum class Section(val label: String, val icon: ImageVector) {
    Live("Direct", Icons.Filled.LiveTv), Sports("Sports", Icons.Filled.SportsSoccer), Movies("Films", Icons.Filled.Movie), Series("Séries", Icons.Filled.VideoLibrary),
    Search("Recherche", Icons.Filled.Search), Favorites("Favoris", Icons.Filled.Favorite), History("Historique", Icons.Filled.History),
    Downloads("Téléchargés", Icons.Filled.Download),
    Settings("Paramètres", Icons.Filled.Settings),
}

fun main() {
    // DirectX (Skiko's default on Windows) leaves black strips on hover effects with some graphics cards: OpenGL unless
    // the user picked another mode in Settings (or LUMEN_RENDER is set).
    System.setProperty("skiko.renderApi", System.getenv("LUMEN_RENDER") ?: Store.renderApi)
    app()
}

private fun app() = application {
    val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp))
    Window(onCloseRequest = { PlayerWindow.release(); exitApplication() }, state = windowState, title = "Lumen TV",
        icon = painterResource("icon.png")) {
        LumenTheme {
            val scope = rememberCoroutineScope()
            val state = remember { AppState(scope) }
            var unlocked by remember { mutableStateOf(Access.isUnlocked()) }
            if (!unlocked) AccessScreen { unlocked = true } else {
                LaunchedEffect(Unit) { state.start() }
                Shell(state)
            }
        }
    }
}

@Composable
fun Shell(state: AppState) {
    // LUMEN_SECTION opens a given screen at launch (used for screenshots).
    val start = System.getenv("LUMEN_SECTION")?.let { s -> Section.entries.firstOrNull { it.name.equals(s, true) } }
    var section by remember { mutableStateOf(start ?: if (state.playlists.isEmpty()) Section.Settings else Section.Live) }
    Row(Modifier.fillMaxSize().background(Pal.bg)) {
        Column(Modifier.width(220.dp).fillMaxHeight().background(Pal.side).padding(12.dp)) {
            Text("Lumen TV", style = MaterialTheme.typography.headlineSmall, color = Pal.text, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
            Text(state.active?.name ?: "Aucune playlist", style = MaterialTheme.typography.bodySmall, color = Pal.dim, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
            Section.entries.forEach { s ->
                NavigationDrawerItem(
                    label = { Text(s.label) }, icon = { Icon(s.icon, null) }, selected = s == section, onClick = { section = s },
                    colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = Pal.accent.copy(alpha = 0.22f), unselectedContainerColor = Pal.side,
                        selectedTextColor = Pal.text, unselectedTextColor = Pal.dim, selectedIconColor = Pal.accent, unselectedIconColor = Pal.dim),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            val profile = state.profileName
            Text(
                when {
                    profile == null -> "Pas de profil en ligne"
                    state.syncing -> "Profil « $profile » · synchronisation…"
                    state.syncMessage != null -> "Profil « $profile » · ${state.syncMessage}"
                    else -> "Profil « $profile » · à jour"
                }, style = MaterialTheme.typography.bodySmall, color = if (state.syncMessage != null) Pal.danger else Pal.dim, modifier = Modifier.padding(8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
            when {
                section == Section.Settings -> SettingsScreen(state)
                section == Section.Downloads -> DownloadsScreen(state)
                state.active == null -> Empty("Aucune playlist", "Ajoutez votre compte Xtream ou votre playlist M3U dans Paramètres, ou connectez votre profil en ligne.")
                state.catalog == null && state.loading -> Empty("Chargement de la playlist…", "La première fois, cela peut prendre une minute.")
                state.catalog == null -> Empty("Playlist indisponible", state.loadError ?: "")
                else -> when (section) {
                    Section.Live -> LiveScreen(state)
                    Section.Sports -> SportsScreen(state)
                    Section.Movies -> MoviesScreen(state)
                    Section.Series -> SeriesScreen(state)
                    Section.Search -> SearchScreen(state)
                    Section.Favorites -> FavoritesScreen(state)
                    Section.History -> HistoryScreen(state)
                    Section.Settings, Section.Downloads -> Unit
                }
            }
            state.toast?.let { t ->
                Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), shape = RoundedCornerShape(24.dp), color = Pal.panelHigh) {
                    Text(t, color = Pal.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
fun Empty(title: String, text: String) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = Pal.text)
    Spacer(Modifier.height(8.dp))
    Text(text, color = Pal.dim)
}

@Composable
fun Badge(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = Pal.text,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Pal.panelHigh).padding(horizontal = 6.dp, vertical = 2.dp))

