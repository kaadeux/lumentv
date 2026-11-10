@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import com.lumentv.core.dayLabel
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.ProgramEntity
import com.lumentv.data.repo.GroupInfo
import com.lumentv.data.repo.Groups
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.components.EmptyState
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.Loading
import com.lumentv.ui.components.Logo
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Grid size: 2 h of programmes beside 150 dp of channel names on the TV; 90 min beside the logos on a phone. */
private object Grid {
    var windowMin = 120
    var dpPerMin = 4.4f
    var channelCol = 150
}
private val WINDOW_MIN get() = Grid.windowMin
private val DP_PER_MIN get() = Grid.dpPerMin
private val CHANNEL_COL get() = Grid.channelCol

private data class FocusTarget(val channelId: Long, val time: Long)

@Composable
fun GuideScreen(vm: MainViewModel) {
    val active by vm.active.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val epgVersion by vm.graph.epg.version.collectAsStateWithLifecycle()
    val changes by vm.graph.library.changes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = active ?: return EmptyState("Aucune playlist", "Ajoutez une playlist dans les paramètres.")
    if (p.lastEpgRefresh == 0L) return EmptyState("Guide TV pas encore chargé",
        "Il s'affiche dès qu'il est téléchargé : quelques minutes pour une grosse liste. Les chaînes se regardent déjà depuis l'onglet TV.")
    val phone = com.lumentv.ui.LocalPhone.current
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    if (phone) { Grid.windowMin = 90; Grid.channelCol = 58; Grid.dpPerMin = ((screenWidth - 24 - 58 - 6) / 90f).coerceAtLeast(2f) }

    var group by remember(p.id) { mutableStateOf(vm.liveGroup ?: Groups.ALL) }
    var chooseGroup by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    var windowStart by remember { mutableLongStateOf(floorTo30(now) - 30 * 60_000L) }
    val windowEnd = windowStart + WINDOW_MIN * 60_000L
    var target by remember { mutableStateOf<FocusTarget?>(null) }
    var selected by remember { mutableStateOf<Pair<ChannelRow, ProgramEntity?>?>(null) }
    var details by remember { mutableStateOf<Pair<ChannelRow, ProgramEntity>?>(null) }

    val channels by produceState<List<ChannelRow>?>(null, p.id, group, changes, settings.showHidden) {
        val locked = vm.graph.library.lockedGroups(p.id) - vm.unlocked.value
        value = vm.graph.library.channels(p.id, group, settings.showHidden, settings.sort).filter { it.ch.groupName !in locked }
    }
    val groups by produceState<List<GroupInfo>>(emptyList(), p.id) { value = vm.graph.library.groups(p.id, false) }
    val minStart = now - (channels?.maxOfOrNull { it.ch.catchupDays }?.coerceAtLeast(1) ?: 1) * 86_400_000L
    val maxStart = now + settings.epgDays * 86_400_000L

    fun open(row: ChannelRow, pr: ProgramEntity?) {
        val t = System.currentTimeMillis()
        when {
            pr == null || (pr.start <= t && pr.stop > t) -> PlayerLauncher.live(context, row.ch.id, group)
            pr.stop <= t -> scope.launch {
                if (vm.graph.resolver.canCatchup(row.ch)) PlayerLauncher.catchup(context, row.ch.id, pr)
                else details = row to pr
            }
            else -> details = row to pr
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Phone: no remote arrows to move in time, so buttons do it; a tap on a programme shows its card.
        if (phone) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(when (group) { Groups.ALL -> "Groupe : toutes les chaînes  ▾"; Groups.FAVORITES -> "Groupe : favoris  ▾"; else -> "Groupe : $group  ▾" }, { chooseGroup = true },
                Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun move(minutes: Int) { val next = windowStart + minutes * 60_000L; if (next in (minStart - WINDOW_MIN * 60_000L)..maxStart) windowStart = next }
                TvButton("◀ 1 h", { move(-60) }, Modifier.weight(1f))
                TvButton("Maintenant", { windowStart = floorTo30(System.currentTimeMillis()) - 30 * 60_000L }, Modifier.weight(1.3f))
                TvButton("1 h ▶", { move(60) }, Modifier.weight(1f))
            }
        } else
        // Header: focused programme
        Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val sel = selected
                if (sel?.second != null) {
                    val pr = sel.second!!
                    Text(pr.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${sel.first.ch.name} · ${dayLabel(pr.start)} ${timeRange(pr.start, pr.stop)}", style = MaterialTheme.typography.bodyMedium, color = Palette.Accent)
                    Text(pr.description ?: "", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("Guide TV", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
                    Text(sel?.first?.ch?.name?.let { "$it · aucune information" } ?: "Gauche / Droite : changer d'horaire · OK : regarder", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                }
            }
            TvButton(when (group) { Groups.ALL -> "Toutes les chaînes"; Groups.FAVORITES -> "Favoris"; else -> group }, { chooseGroup = true })
        }

        if (phone) Text(dayLabel(windowStart), style = MaterialTheme.typography.labelLarge, color = Palette.Text, modifier = Modifier.padding(bottom = 4.dp))
        // Time ruler
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            // Phone: the column holds only logos, the day goes on its own line above.
            Text(if (phone) "" else dayLabel(windowStart), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, modifier = Modifier.width(CHANNEL_COL.dp))
            Box(Modifier.width((WINDOW_MIN * DP_PER_MIN).dp)) {
                for (i in 0 until WINDOW_MIN / 30) {
                    val t = windowStart + i * 30 * 60_000L
                    Text(hhmm(t), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, modifier = Modifier.offset(x = (i * 30 * DP_PER_MIN).dp))
                }
                if (now in windowStart until windowEnd) {
                    Box(Modifier.offset(x = ((now - windowStart) / 60_000f * DP_PER_MIN).dp).width(2.dp).height(16.dp).background(Palette.Accent))
                }
            }
        }

        val list = channels
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState("Aucune chaîne", "Ce groupe est vide.")
            else -> {
                val state = rememberLazyListState()
                LaunchedEffect(list) {
                    val idx = list.indexOfFirst { it.ch.id == vm.guideChannel }
                    if (idx >= 0) { state.scrollToItem(idx); target = FocusTarget(list[idx].ch.id, now); vm.guideChannel = 0 }
                }
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.ch.id }) { row ->
                        GuideRow(
                            vm, row, windowStart, windowEnd, epgVersion, target,
                            onFocus = { pr -> selected = row to pr },
                            onClick = { pr -> if (phone && pr != null) details = row to pr else open(row, pr) },
                            onChannel = { PlayerLauncher.live(context, row.ch.id, group) },
                            onLongClick = { pr -> if (pr != null) details = row to pr },
                            onEdge = { forward, time ->
                                val next = windowStart + (if (forward) 60 else -60) * 60_000L
                                if (next in (minStart - WINDOW_MIN * 60_000L)..maxStart) {
                                    windowStart = next
                                    target = FocusTarget(row.ch.id, if (forward) time + 1 else time - 1)
                                    true
                                } else false
                            },
                        )
                    }
                }
            }
        }
    }

    if (chooseGroup) {
        OptionsDialog("Groupe", listOf(
            MenuOption("Toutes les chaînes", group == Groups.ALL) { group = Groups.ALL },
            MenuOption("Favoris", group == Groups.FAVORITES) { group = Groups.FAVORITES },
        ) + groups.map { g -> MenuOption(g.name, g.name == group) { group = g.name; vm.liveGroup = g.name } }, onDismiss = { chooseGroup = false })
    }

    details?.let { (row, pr) ->
        val t = System.currentTimeMillis()
        val options = buildList {
            if (pr.start <= t && pr.stop > t) add(MenuOption("Regarder en direct") { PlayerLauncher.live(context, row.ch.id, group) })
            if (pr.start <= t && pr.stop > t && row.ch.catchupDays > 0) add(MenuOption("Regarder depuis le début") { PlayerLauncher.catchup(context, row.ch.id, pr) })
            if (pr.stop <= t && row.ch.catchupDays > 0) add(MenuOption("Regarder en rattrapage") { PlayerLauncher.catchup(context, row.ch.id, pr) })
            add(MenuOption("Fermer") {})
        }
        OptionsDialog(pr.title, options, onDismiss = { details = null },
            subtitle = "${row.ch.name} · ${dayLabel(pr.start)} ${timeRange(pr.start, pr.stop)}\n${pr.description ?: "Pas de description."}")
    }
}

@Composable
private fun GuideRow(
    vm: MainViewModel,
    row: ChannelRow,
    windowStart: Long,
    windowEnd: Long,
    epgVersion: Int,
    target: FocusTarget?,
    onFocus: (ProgramEntity?) -> Unit,
    onClick: (ProgramEntity?) -> Unit,
    onLongClick: (ProgramEntity?) -> Unit,
    onEdge: (forward: Boolean, time: Long) -> Boolean,
    onChannel: () -> Unit = {},
) {
    val programs by produceState<List<ProgramEntity>?>(null, row.ch.id, windowStart, epgVersion) {
        value = vm.graph.epg.programs(row, windowStart, windowEnd)
    }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        val phone = com.lumentv.ui.LocalPhone.current
        Row(
            Modifier.width(CHANNEL_COL.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Palette.Panel)
                .then(if (phone) Modifier.clickable(onClick = onChannel) else Modifier).padding(horizontal = if (phone) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (phone) Arrangement.Center else Arrangement.Start,
        ) {
            Logo(row.ch.logo, row.ch.name, size = if (phone) 40.dp else 32.dp)
            if (!phone) {
                Spacer(Modifier.width(8.dp))
                Text(row.ch.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Palette.Text)
            }
        }
        Spacer(Modifier.width(4.dp))
        val list = programs.orEmpty()
        val windowMin = WINDOW_MIN
        if (list.isEmpty()) {
            Cell(null, windowMin * DP_PER_MIN, isFirst = true, isLast = true, row, target, windowStart, onFocus, onClick, onLongClick, onEdge)
        } else {
            var cursor = windowStart
            list.forEachIndexed { i, pr ->
                val s = maxOf(pr.start, windowStart)
                val e = minOf(pr.stop, windowEnd)
                if (s > cursor) Spacer(Modifier.width(((s - cursor) / 60_000f * DP_PER_MIN).dp))
                val w = ((e - s) / 60_000f * DP_PER_MIN).coerceAtLeast(6f)
                Cell(pr, w, i == 0, i == list.lastIndex, row, target, windowStart, onFocus, onClick, onLongClick, onEdge)
                cursor = e
            }
        }
    }
}

@Composable
private fun Cell(
    pr: ProgramEntity?,
    widthDp: Float,
    isFirst: Boolean,
    isLast: Boolean,
    row: ChannelRow,
    target: FocusTarget?,
    windowStart: Long,
    onFocus: (ProgramEntity?) -> Unit,
    onClick: (ProgramEntity?) -> Unit,
    onLongClick: (ProgramEntity?) -> Unit,
    onEdge: (forward: Boolean, time: Long) -> Boolean,
) {
    val requester = remember { FocusRequester() }
    val now = System.currentTimeMillis()
    val airing = pr != null && pr.start <= now && pr.stop > now
    val past = pr != null && pr.stop <= now
    FocusSurface(
        onClick = { onClick(pr) },
        onLongClick = { onLongClick(pr) },
        container = if (airing) Palette.PanelHigh else Palette.Panel,
        focusedScale = 1f,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .width(widthDp.dp).fillMaxHeight().padding(end = 2.dp)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocus(pr) }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.DirectionRight && isLast -> onEdge(true, pr?.stop ?: (windowStart + WINDOW_MIN * 60_000L))
                    e.key == Key.DirectionLeft && isFirst -> onEdge(false, pr?.start ?: windowStart)
                    else -> false
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                pr?.title ?: "Aucune information", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (past && row.ch.catchupDays == 0) Palette.TextDim else androidx.compose.ui.graphics.Color.Unspecified,
            )
            if (pr != null && widthDp > 60) Text(hhmm(pr.start) + if (past && row.ch.catchupDays > 0) "  ⟲" else "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
    LaunchedEffect(target, pr) {
        val t = target ?: return@LaunchedEffect
        if (t.channelId == row.ch.id && (pr == null || (t.time >= pr.start && t.time < pr.stop))) runCatching { requester.requestFocus() }
    }
}

private fun floorTo30(t: Long) = t - t % (30 * 60_000L)
private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))
