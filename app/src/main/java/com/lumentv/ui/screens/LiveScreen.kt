@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import com.lumentv.ui.components.enterHere
import com.lumentv.ui.components.enterOn
import com.lumentv.ui.components.hint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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

    var previewFor by remember { mutableStateOf<ChannelRow?>(null) }
    var groupPicker by remember { mutableStateOf(false) }
    val phone = com.lumentv.ui.LocalPhone.current
    val channelColumn: @Composable () -> Unit = {
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
                    if (group == Groups.FAVORITES) hint("Maintenez OK sur une chaîne pour l'ajouter aux favoris.", "Touchez ⋮ à droite d'une chaîne, puis « Ajouter aux favoris ».") else "Ce groupe est vide ou toutes ses chaînes sont masquées.",
                )
                else -> {
                    val state = rememberLazyListState()
                    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.ch.id }) { row ->
                            ChannelItem(
                                vm, row, epgVersion,
                                onClick = {
                                    if (row.ch.groupName in vm.lockedCache && row.ch.groupName !in vm.unlocked.value) { pinFor = row.ch.groupName; pinError = null }
                                    // The preview's stream stops first: a one-screen subscription refuses a second one.
                                    else { PreviewStream.stop(); PlayerLauncher.live(context, row.ch.id, group) }
                                },
                                onLongClick = { menuFor = row },
                                onFocus = { focused = row },
                                onMore = if (phone) ({ menuFor = row }) else null,
                            )
                        }
                    }
                }
            }
            }

    if (phone) Column(Modifier.fillMaxSize()) {
        // Phone: the group is a big button (the list opens in a window), then the channels over the whole width.
        val g = groups?.firstOrNull { it.name == group }
        FocusSurface(onClick = { groupPicker = true }, onLongClick = { g?.let { if (it.name != Groups.ALL && it.name != Groups.FAVORITES) groupMenu = it } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), container = Palette.PanelHigh) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Groupe", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    Text(when (group) { Groups.ALL -> "Toutes les chaînes"; Groups.FAVORITES -> "★ Favoris"; else -> group },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Changer  ▾", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
            }
        }
        channelColumn()
    } else Column(Modifier.fillMaxSize()) {
        // V2: the groups as tabs across the top ("Mes chaînes" first), then the channels and the focused one's programme.
        val specials = listOf(GroupInfo(Groups.FAVORITES, 0, false, false), GroupInfo(Groups.ALL, p.channelCount, false, false))
        val chosen = com.lumentv.ui.components.rememberEnterTarget()
        Row(Modifier.padding(bottom = 12.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xB3141416)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(26.dp)).padding(4.dp)) {
            LazyRow(Modifier.enterOn(chosen), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(specials + groups.orEmpty(), key = { it.name }) { g ->
                    val on = g.name == group
                    FocusSurface(
                        onClick = {
                            if (g.locked && g.name !in vm.unlocked.value) { pinFor = g.name; pinError = null }
                            group = g.name; vm.liveGroup = g.name
                        },
                        onLongClick = { if (g.name != Groups.ALL && g.name != Groups.FAVORITES) groupMenu = g },
                        modifier = if (on) Modifier.enterHere(chosen) else Modifier, shape = RoundedCornerShape(22.dp),
                        container = if (on) Color(0x33FFFFFF) else Color.Transparent, focusedScale = 1.04f,
                    ) {
                        Text(when (g.name) { Groups.ALL -> "Toutes"; Groups.FAVORITES -> "★  Mes chaînes"; else -> g.name } + if (g.locked) "  🔒" else "",
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(0.46f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Color(0x99141416)).padding(10.dp)) { channelColumn() }
            Column(Modifier.weight(0.54f).fillMaxHeight()) {
                val f = focused
                if (f == null) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(Palette.Panel), contentAlignment = Alignment.Center) {
                    Text("Choisissez une chaîne", color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
                } else LivePreview(vm, f, epgVersion)
            }
        }
    }

    LaunchedEffect(p.id, changes) { vm.lockedCache = vm.graph.library.lockedGroups(p.id) }

    menuFor?.let { row ->
        val canCatchup = row.ch.catchupDays > 0
        OptionsDialog(row.ch.name, buildList {
            if (phone) add(MenuOption("Voir le programme") { previewFor = row })
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

    previewFor?.let { row ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { previewFor = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(20.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                ChannelPreview(vm, row, epgVersion)
                TvButton("Regarder", { previewFor = null; PlayerLauncher.live(context, row.ch.id, group) }, primary = true)
            }
        }
    }

    if (groupPicker) {
        val all = listOf(GroupInfo(Groups.ALL, p.channelCount, false, false), GroupInfo(Groups.FAVORITES, 0, false, false)) + groups.orEmpty()
        val current = groups?.firstOrNull { it.name == group }
        OptionsDialog("Groupes", all.map { g ->
            MenuOption(when (g.name) { Groups.ALL -> "Toutes les chaînes"; Groups.FAVORITES -> "★ Favoris"; else -> g.name } +
                (if (g.locked) "  🔒" else "") + (if (g.count > 0) "  ·  ${g.count}" else ""), checked = g.name == group) {
                if (g.locked && g.name !in vm.unlocked.value) { pinFor = g.name; pinError = null }
                group = g.name; vm.liveGroup = g.name
            }
        } + listOfNotNull(current?.let { g -> MenuOption("⚙  Masquer ou verrouiller « ${g.name} »…") { groupMenu = g } }), onDismiss = { groupPicker = false })
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
private fun ChannelItem(vm: MainViewModel, row: ChannelRow, epgVersion: Int, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit,
                        onMore: (() -> Unit)? = null) {
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
                    if (row.ch.catchupDays > 0) { Spacer(Modifier.width(8.dp)); Badge("Rattrapage", Palette.PanelHigh) }
                }
                val now = nowNext?.now
                if (now != null) {
                    Text(now.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(progressOf(now.start, now.stop), Modifier.fillMaxWidth(0.6f))
                }
            }
            // Phone: options without having to know about the long press.
            if (onMore != null) Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
                Text("⋮", style = MaterialTheme.typography.titleLarge, color = Palette.TextDim)
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


/** The focused channel, large: what is on (with its progress) over a tinted card, then what comes next. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LivePreview(vm: MainViewModel, row: ChannelRow, epgVersion: Int) {
    val programs by produceState(emptyList<com.lumentv.data.db.ProgramEntity>(), row.ch.id, epgVersion) {
        val now = System.currentTimeMillis()
        value = vm.graph.epg.programs(row, now - 1, now + 8 * 3_600_000L).take(4)
    }
    val now = programs.firstOrNull()
    // The channel itself plays in the card once the focus rests on it; its programme is written over the picture.
    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF2A1E19), Color(0xFF15161A), Color(0xFF101114))))) {
        LiveVideo(vm, row)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.25f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xEE000000))))
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(row.ch.logo, row.ch.name, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(row.ch.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("N° ${row.ch.number}" + (if (row.ch.catchupDays > 0) "  ·  Rattrapage ${row.ch.catchupDays} j" else ""), style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF))
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp)) {
            Text("●  EN DIRECT", style = MaterialTheme.typography.labelMedium, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 4.dp))
            Text(now?.title ?: "Pas d'information de programme", style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            if (now != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)
                Text(f.format(java.util.Date(now.start)), style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF))
                ProgressLine(progressOf(now.start, now.stop), Modifier.weight(1f).padding(horizontal = 10.dp))
                Text(f.format(java.util.Date(now.stop)), style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Panel).padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            programs.getOrNull(1)?.let { n ->
                Text("Ensuite · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(n.start)), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                Text(n.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } ?: Text("Programme suivant inconnu", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        }
        Text("OK : regarder  ·  Maintenir OK : options", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}

/**
 * The focused channel playing in the preview card, without sound, once the focus has rested on it for a moment (a
 * quick scroll does not open every channel). One player for the whole screen: moving to another channel stops the
 * stream at once and closes its connection (the provider then frees the screen sooner), so the next one comes quicker.
 * It stops when the full-screen player opens (the menus go to the background) and while the mini-player plays.
 */
@Composable
private fun LiveVideo(vm: MainViewModel, row: ChannelRow) {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val mini by vm.graph.miniPlayer.collectAsStateWithLifecycle()
    var controller by remember { mutableStateOf<com.lumentv.player.PlayerController?>(null) }
    var shownId by remember { mutableStateOf(0L) }
    var started by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(mini, lifecycle) {
        fun release() {
            controller?.let { com.lumentv.data.xtream.ScreenGuard.stopped(); it.release() }
            controller = null; started = false; shownId = 0
            closeIdle(vm)
            PreviewStream.stop = {}
        }
        fun create() {
            if (mini != null || controller != null) return
            val c = com.lumentv.player.PlayerController(context, vm.graph.streamHttp, kotlinx.coroutines.runBlocking { vm.graph.settings.current() })
            c.player.volume = 0f
            controller = c
            com.lumentv.data.xtream.ScreenGuard.started()
            PreviewStream.stop = ::release
        }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) create()
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) release()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); release() }
    }
    val c = controller
    // The channel's logo until its picture is there.
    val cover: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A1E19), Color(0xFF15161A), Color(0xFF101114)))), contentAlignment = Alignment.Center) {
            Logo(row.ch.logo, row.ch.name, size = 96.dp)
        }
    }
    if (c == null) return cover()
    LaunchedEffect(c, row.ch.id) {
        // The channel left: its stream stops now and its connection is closed, not left open behind the new one.
        if (shownId != row.ch.id) { started = false; c.player.stop(); closeIdle(vm) }
        kotlinx.coroutines.delay(600)
        shownId = row.ch.id
        runCatching { c.play(vm.graph.resolver.channel(row.ch)) }
    }
    val st by c.state.collectAsStateWithLifecycle()
    LaunchedEffect(st.playing, shownId) { if (st.playing && shownId == row.ch.id) started = true }
    // The picture stays attached to the player the whole time (switching channels keeps it); the logo covers it
    // until the new channel's first frames.
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { androidx.media3.ui.PlayerView(it).apply {
            useController = false; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM; isFocusable = false
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT); setKeepContentOnPlayerReset(false)
        } },
        update = { if (it.player !== c.player) it.player = c.player },
        modifier = Modifier.fillMaxSize(),
    )
    if (!started) cover()
}

/** Closes the connections left open (a socket's close writes to the network: off the main thread). */
private fun closeIdle(vm: MainViewModel) { vm.graph.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { vm.graph.streamHttp.connectionPool.evictAll() } }

/** Stops the preview's stream at once (before the full-screen player opens). */
object PreviewStream {
    var stop: () -> Unit = {}
}
