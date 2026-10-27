@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.core.normalize
import com.lumentv.data.db.ChannelRow
import com.lumentv.ui.components.FilterField
import com.lumentv.data.db.PrefKind
import com.lumentv.data.repo.GroupInfo
import com.lumentv.data.repo.Groups
import com.lumentv.data.settings.Keys
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.Screen
import com.lumentv.ui.components.Badge
import com.lumentv.ui.components.EmptyState
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.Loading
import com.lumentv.ui.components.Logo
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.PinDialog
import com.lumentv.ui.components.ProgressLine
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(vm: MainViewModel) {
    val active by vm.active.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val changes by vm.graph.library.changes.collectAsStateWithLifecycle()
    val epgVersion by vm.graph.epg.version.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val p = active ?: return EmptyState("Aucune playlist", "Ajoutez une playlist M3U ou un compte Xtream dans les paramètres.")

    if (p.channelCount == 0) {
        return EmptyState(
            if (p.id in busy) "Chargement de la playlist…" else "Aucune chaîne",
            p.lastError ?: "Cette playlist ne contient pas de chaînes en direct.",
        ) { TvButton("Actualiser", { vm.refresh(p.id) }, primary = true) }
    }

    var group by remember(p.id) { mutableStateOf(vm.liveGroup ?: Groups.ALL) }
    val groups by produceState<List<GroupInfo>?>(null, p.id, changes, settings.showHidden, p.lastRefresh) {
        value = vm.graph.library.groups(p.id, settings.showHidden)
    }
    var pinFor by remember { mutableStateOf<String?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val locked = groups?.firstOrNull { it.name == group }?.locked == true && group !in vm.unlocked.value
    val channels by produceState<List<ChannelRow>?>(null, p.id, group, changes, settings.showHidden, settings.sort, p.lastRefresh, locked) {
        value = if (locked) emptyList() else vm.graph.library.channels(p.id, group, settings.showHidden, settings.sort)
    }
    var focused by remember { mutableStateOf<ChannelRow?>(null) }
    var filter by remember(p.id, group) { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<ChannelRow?>(null) }
    var groupMenu by remember { mutableStateOf<GroupInfo?>(null) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Groups
        Column(Modifier.width(190.dp).fillMaxHeight()) {
            Text("Groupes", style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.padding(bottom = 10.dp))
            val specials = listOf(GroupInfo(Groups.ALL, p.channelCount, false, false), GroupInfo(Groups.FAVORITES, 0, false, false))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(specials + groups.orEmpty(), key = { it.name }) { g ->
                    FocusSurface(
                        onClick = {
                            if (g.locked && g.name !in vm.unlocked.value) { pinFor = g.name; pinError = null }
                            group = g.name; vm.liveGroup = g.name
                        },
                        onLongClick = { if (g.name != Groups.ALL && g.name != Groups.FAVORITES) groupMenu = g },
                        modifier = Modifier.fillMaxWidth(), selected = g.name == group, focusedScale = 1.02f,
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (g.name) { Groups.ALL -> "Toutes les chaînes"; Groups.FAVORITES -> "★ Favoris"; else -> g.name },
                                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (g.locked) Text("🔒", style = MaterialTheme.typography.bodySmall)
                            if (g.hidden) Text(" masqué", style = MaterialTheme.typography.bodySmall)
                            if (g.count > 0) Text("  ${g.count}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Channels
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (group) { Groups.ALL -> "Chaînes"; Groups.FAVORITES -> "Favoris"; else -> group },
                    style = MaterialTheme.typography.titleLarge, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (group != Groups.FAVORITES) TvButton(
                    when (settings.sort) { "az" -> "A → Z"; "za" -> "Z → A"; else -> "Ordre" },
                    { scope.launch { vm.graph.settings.set(Keys.sort, when (settings.sort) { "provider" -> "az"; "az" -> "za"; else -> "provider" }) } },
                )
            }
            FilterField(filter) { filter = it }
            val list = channels?.let { all ->
                val f = normalize(filter)
                if (f.isEmpty()) all else all.filter { it.ch.nameNorm.contains(f) || it.ch.number.toString().startsWith(f) }
            }
            when {
                locked -> EmptyState("Groupe verrouillé", "Saisissez le code PIN pour afficher ces chaînes.") {
                    TvButton("Déverrouiller", { pinFor = group; pinError = null }, primary = true)
                }
                list == null -> Loading()
                list.isEmpty() && filter.isNotBlank() -> EmptyState("Aucune chaîne", "Rien ne correspond à « $filter » dans cette liste.")
                list.isEmpty() -> EmptyState(
                    if (group == Groups.FAVORITES) "Pas encore de favoris" else "Aucune chaîne",
                    if (group == Groups.FAVORITES) "Maintenez OK sur une chaîne pour l'ajouter aux favoris." else "Ce groupe est vide ou toutes ses chaînes sont masquées.",
                )
                else -> {
                    val state = rememberLazyListState()
                    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.ch.id }) { row ->
                            ChannelItem(
                                vm, row, epgVersion,
                                onClick = {
                                    if (row.ch.groupName in vm.lockedCache && row.ch.groupName !in vm.unlocked.value) { pinFor = row.ch.groupName; pinError = null }
                                    else PlayerLauncher.live(context, row.ch.id, group)
                                },
                                onLongClick = { menuFor = row },
                                onFocus = { focused = row },
                            )
                        }
                    }
                }
            }
        }

        // Preview of the focused channel
        Column(Modifier.width(250.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Palette.Panel).padding(18.dp)) {
            val f = focused
            if (f == null) {
                Text("Sélectionnez une chaîne", color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
            } else {
                ChannelPreview(vm, f, epgVersion)
            }
        }
    }

    LaunchedEffect(p.id, changes) { vm.lockedCache = vm.graph.library.lockedGroups(p.id) }

    menuFor?.let { row ->
        val canCatchup = row.ch.catchupDays > 0
        OptionsDialog(row.ch.name, buildList {
            add(MenuOption(if (row.isFavorite) "Retirer des favoris" else "Ajouter aux favoris") {
                scope.launch { val fav = vm.graph.library.toggleFavorite(row.ch.playlistId, PrefKind.CHANNEL, row.ch.uid); vm.show(if (fav) "Ajouté aux favoris" else "Retiré des favoris") }
            })
            if (group == Groups.FAVORITES) {
                add(MenuOption("Monter dans les favoris") { scope.launch { vm.graph.library.moveFavorite(row.ch.playlistId, row.ch.uid, -1) } })
                add(MenuOption("Descendre dans les favoris") { scope.launch { vm.graph.library.moveFavorite(row.ch.playlistId, row.ch.uid, 1) } })
            }
            add(MenuOption(if (row.isHidden) "Afficher la chaîne" else "Masquer la chaîne") {
                scope.launch { vm.graph.library.setHidden(row.ch.playlistId, PrefKind.CHANNEL, row.ch.uid, !row.isHidden) }
            })
            add(MenuOption("Ouvrir en multi-écran") { PlayerLauncher.multiView(context, longArrayOf(row.ch.id)) })
            add(MenuOption("Corriger le guide TV de la chaîne") { vm.open(Screen.EpgFix(row.ch.id)) })
            if (canCatchup) add(MenuOption("Rattrapage (${row.ch.catchupDays} j) : ouvrir le guide") { vm.guideChannel = row.ch.id; vm.openRoot(Screen.Guide) })
        }, onDismiss = { menuFor = null })
    }

    groupMenu?.let { g ->
        OptionsDialog(g.name, listOf(
            MenuOption(if (g.hidden) "Afficher le groupe" else "Masquer le groupe") {
                scope.launch { vm.graph.library.setHidden(p.id, PrefKind.GROUP, g.name, !g.hidden) }
            },
            MenuOption(if (g.locked) "Retirer le verrou parental" else "Verrouiller par code PIN") {
                scope.launch {
                    if (!g.locked && settings.pinHash.isEmpty()) vm.show("Définissez d'abord un code PIN dans Paramètres › Contrôle parental.")
                    else if (g.locked) { pinFor = "\u0002unlock:" + g.name; pinError = null }
                    else vm.graph.library.setLocked(p.id, g.name, true)
                }
            },
        ), onDismiss = { groupMenu = null })
    }

    pinFor?.let { target ->
        PinDialog("Code PIN", error = pinError, onDismiss = { pinFor = null }, onPin = { pin ->
            scope.launch {
                if (vm.graph.settings.checkPin(pin)) {
                    if (target.startsWith("\u0002unlock:")) vm.graph.library.setLocked(p.id, target.removePrefix("\u0002unlock:"), false)
                    else vm.unlocked.value = vm.unlocked.value + target
                    pinFor = null
                } else pinError = "Code incorrect"
            }
        })
    }
}

@Composable
private fun ChannelItem(vm: MainViewModel, row: ChannelRow, epgVersion: Int, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit) {
    val nowNext by produceState<com.lumentv.data.repo.NowNext?>(null, row.ch.id, epgVersion) { value = vm.graph.epg.nowNext(row) }
    FocusSurface(
        onClick = onClick, onLongClick = onLongClick, focusedScale = 1.01f,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.ch.number.toString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(34.dp))
            Logo(row.ch.logo, row.ch.name)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (row.isFavorite) Text("  ★", style = MaterialTheme.typography.bodyMedium, color = Palette.Gold)
                    if (row.ch.catchupDays > 0) { Spacer(Modifier.width(8.dp)); Badge("Replay", Palette.PanelHigh) }
                }
                val now = nowNext?.now
                if (now != null) {
                    Text(now.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(progressOf(now.start, now.stop), Modifier.fillMaxWidth(0.6f))
                }
            }
        }
    }
}

@Composable
private fun ChannelPreview(vm: MainViewModel, row: ChannelRow, epgVersion: Int) {
    val programs by produceState(emptyList<com.lumentv.data.db.ProgramEntity>(), row.ch.id, epgVersion) {
        val now = System.currentTimeMillis()
        value = vm.graph.epg.programs(row, now - 1, now + 8 * 3_600_000L).take(6)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(row.ch.logo, row.ch.name, size = 64.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(row.ch.name, style = MaterialTheme.typography.titleLarge, color = Palette.Text, maxLines = 2)
            Text("N° ${row.ch.number} · ${row.ch.groupName}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
        }
    }
    Spacer(Modifier.height(16.dp))
    if (programs.isEmpty()) {
        Text("Aucune information de programme pour cette chaîne.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
    } else {
        programs.forEachIndexed { i, pr ->
            Text(timeRange(pr.start, pr.stop), style = MaterialTheme.typography.labelMedium, color = if (i == 0) Palette.Accent else Palette.TextDim)
            Text(pr.title, style = if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = Palette.Text, maxLines = 2)
            if (i == 0) {
                Spacer(Modifier.height(4.dp))
                ProgressLine(progressOf(pr.start, pr.stop), Modifier.fillMaxWidth())
                pr.description?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

fun progressOf(start: Long, stop: Long, now: Long = System.currentTimeMillis()): Float =
    if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)

fun timeRange(start: Long, stop: Long): String {
    val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)
    return f.format(java.util.Date(start)) + " – " + f.format(java.util.Date(stop))
}

