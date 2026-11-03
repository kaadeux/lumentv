@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.ui.components.LumenIcons
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.theme.Palette

/** A destination of the phone: in the bottom bar, or in the "Plus" list. */
private class Dest(val screen: Screen, val label: String, val icon: ImageVector, val hint: String = "")

/**
 * The phone's frame: a title bar (back arrow on sub-screens, search), the screen, and five big tabs at the bottom.
 * The TV's side menu has ten entries; the four used most are tabs here, the others are one tap away in "Plus".
 */
@Composable
fun PhoneShell(vm: MainViewModel, firstLaunch: Boolean, screen: @Composable (Screen) -> Unit) {
    val active by vm.active.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val p = active
    val hasLive = p == null || p.channelCount > 0
    val hasGuide = p != null && p.channelCount > 0 && p.lastEpgRefresh > 0

    val tabs = buildList {
        if (hasLive) add(Dest(Screen.Live, "Direct", Icons.Filled.Home))
        if (p != null && p.movieCount > 0) add(Dest(Screen.Movies, "Films", Icons.Filled.PlayArrow))
        if (p != null && p.seriesCount > 0) add(Dest(Screen.Series, "Séries", Icons.Filled.List))
        if (hasGuide) add(Dest(Screen.Sports, "Sport", LumenIcons.Sports))
        add(Dest(Screen.More, "Plus", Icons.Filled.Menu))
    }
    val more = buildList {
        if (hasGuide) add(Dest(Screen.Guide, "Guide TV", Icons.Filled.DateRange, "Les programmes de toutes les chaînes, et le rattrapage"))
        if (p != null) add(Dest(Screen.Favorites, "Favoris", Icons.Filled.Star, "Vos chaînes, films et séries préférés"))
        if (p != null) add(Dest(Screen.Search, "Recherche", Icons.Filled.Search, "Chaînes, films, séries et programmes"))
        if (p != null) add(Dest(Screen.History, "Historique", Icons.Filled.Refresh, "Reprendre là où vous en étiez"))
        if (p != null) add(Dest(Screen.Recordings, "Téléchargés", LumenIcons.Download, "Films, épisodes et enregistrements sur ce téléphone"))
        add(Dest(Screen.Settings, "Paramètres", Icons.Filled.Settings, "Playlists, profil, lecture, contrôle parental"))
    }

    // Back from a screen reached through "Plus" returns to "Plus", not to Direct.
    fun goBack() { if (vm.stack.size == 1 && more.any { it.screen == vm.root }) vm.openRoot(Screen.More) else vm.back() }
    BackHandler(enabled = vm.stack.size > 1 || vm.root != Screen.Live) { goBack() }
    LaunchedEffect(playlists) {
        if (playlists?.isEmpty() == true && vm.root != Screen.Settings) vm.openRoot(Screen.Settings)
    }
    // "Open the last channel" at launch, as on the TV.
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(firstLaunch, settings.lastChannelId, playlists) {
        if (firstLaunch && playlists != null && settings.startup == "last" && settings.lastChannelId > 0 && !startupDone) {
            startupDone = true
            if (vm.graph.library.channel(settings.lastChannelId) != null) PlayerLauncher.live(ctx, settings.lastChannelId, null)
        }
    }

    val current = vm.current
    val sub = vm.stack.size > 1
    val title = when (current) {
        Screen.Live -> "Direct"; Screen.Movies -> "Films"; Screen.Series -> "Séries"; Screen.Sports -> "Sport"
        Screen.More -> "Plus"; Screen.Guide -> "Guide TV"; Screen.Favorites -> "Favoris"; Screen.Search -> "Recherche"
        Screen.History -> "Historique"; Screen.Recordings -> "Téléchargés"; Screen.Settings -> "Paramètres"
        is Screen.MovieDetail -> "Film"; is Screen.SeriesDetail -> "Série"
        is Screen.PlaylistEdit -> "Playlist"; is Screen.EpgFix -> "Guide de la chaîne"
    }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        // Title bar
        Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sub || vm.root !in tabs.map { it.screen }) IconTap(Icons.AutoMirrored.Filled.ArrowBack, "Retour") { goBack() }
            else Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!sub) Text(p?.name ?: "Aucune playlist", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
            }
            if (p != null && current != Screen.Search) IconTap(Icons.Filled.Search, "Rechercher") { vm.open(Screen.Search) }
        }
        // Screen
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            if (current == Screen.More) MoreList(vm, more) else screen(current)
        }
        // Mini-player (a channel kept playing small while browsing)
        val mini by vm.graph.miniPlayer.collectAsStateWithLifecycle()
        val context = androidx.compose.ui.platform.LocalContext.current
        mini?.let { id ->
            Row(Modifier.fillMaxWidth().background(Palette.AccentSoft).clickable { PlayerLauncher.live(context, id, null); vm.graph.miniPlayer.value = null }
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("▣  Mini-lecteur · toucher pour le plein écran", style = MaterialTheme.typography.labelLarge, color = Palette.Text, modifier = Modifier.weight(1f))
                Text("Fermer", style = MaterialTheme.typography.labelLarge, color = Palette.Accent,
                    modifier = Modifier.clickable { vm.graph.miniPlayer.value = null }.padding(6.dp))
            }
        }
        // Tabs
        Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(vertical = 4.dp)) {
            val inMore = vm.root == Screen.More || more.any { it.screen == vm.root }
            tabs.forEach { t ->
                val on = vm.root == t.screen || (t.screen == Screen.More && inMore)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { vm.openRoot(t.screen) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Palette.AccentSoft else Palette.Panel).padding(horizontal = 18.dp, vertical = 4.dp)) {
                        Icon(t.icon, contentDescription = null, tint = if (on) Palette.Accent else Palette.TextDim, modifier = Modifier.size(24.dp))
                    }
                    Text(t.label, style = MaterialTheme.typography.labelMedium, color = if (on) Palette.Text else Palette.TextDim)
                }
            }
        }
    }
}

@Composable
private fun IconTap(icon: ImageVector, label: String, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Palette.Text, modifier = Modifier.size(24.dp))
    }

/** "Plus": the other destinations as big rows with a line saying what each holds. */
@Composable
private fun MoreList(vm: MainViewModel, items: List<Dest>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.label }) { d ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Panel).clickable { vm.openRoot(d.screen) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(d.icon, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.label, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                    if (d.hint.isNotEmpty()) Text(d.hint, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
                Text("›", style = MaterialTheme.typography.headlineSmall, color = Palette.TextDim)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
