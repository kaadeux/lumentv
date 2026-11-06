package com.lumentv.phone

import com.lumentv.core.dayLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.AppCore
import com.lumentv.core.nowMillis
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.ProgramEntity
import com.lumentv.data.repo.Groups
import kotlinx.coroutines.launch

/** The Android phone's grid: 90 minutes beside the channels' logos. */
private const val WINDOW_MIN = 90
private const val LOGO_COL = 58
private const val HALF_HOUR = 30 * 60_000L
private const val DAY = 86_400_000L

private fun floorTo30(t: Long) = t - t % HALF_HOUR

/**
 * The TV guide as on the Android phone: every channel of a group with its programmes in a grid, moved by the hour;
 * a logo plays the channel, a programme shows its card (live, from the start or catch-up, the channel's programmes).
 */
@Composable
internal fun GuidePage(core: AppCore, p: PlaylistEntity, player: VideoPlayer, open: (Route) -> Unit) {
    val settings by core.settings.flow.collectAsState(null)
    val epgVersion by core.epg.version.collectAsState()
    val changes by core.library.changes.collectAsState()
    val memory = LocalScroll.current
    var group by remember(p.id) { mutableStateOf(memory.values["guide"] as? String ?: Groups.ALL) }
    var chooseGroup by remember { mutableStateOf(false) }
    val now = remember { nowMillis() }
    var windowStart by remember { mutableStateOf(floorTo30(now) - HALF_HOUR) }
    val windowEnd = windowStart + WINDOW_MIN * 60_000L
    var details by remember { mutableStateOf<Pair<ChannelRow, ProgramEntity>?>(null) }
    val scope = rememberCoroutineScope()
    // Locked groups stay out of the guide until their PIN is given (in the TV tab).
    val groups by produceState(emptyList<String>(), p.id, changes) {
        value = core.library.groups(p.id, false).filter { !it.locked || Parental.isUnlocked(p.id, it.name) }.map { it.name }
    }
    val channels by produceState<List<ChannelRow>?>(null, p.id, group, changes) {
        val locked = core.library.lockedGroups(p.id).filterTo(HashSet()) { !Parental.isUnlocked(p.id, it) }
        value = core.library.channels(p.id, group, false, settings?.sort ?: "provider").filter { it.ch.groupName !in locked }
    }
    val minStart = now - (channels?.maxOfOrNull { it.ch.catchupDays }?.coerceAtLeast(1) ?: 1) * DAY
    val maxStart = now + (settings?.epgDays ?: 3) * DAY
    fun move(minutes: Int) {
        val next = windowStart + minutes * 60_000L
        if (next in (minStart - WINDOW_MIN * 60_000L)..maxStart) windowStart = next
    }
    fun label(g: String) = when (g) { Groups.ALL -> "toutes les chaînes"; Groups.FAVORITES -> "favoris"; else -> g }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val dpPerMin = ((maxWidth.value - LOGO_COL - 6) / WINDOW_MIN).coerceAtLeast(2f)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneButton("Groupe : ${label(group)}  ▾", modifier = Modifier.fillMaxWidth()) { chooseGroup = true }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneButton("◀  1 h", modifier = Modifier.weight(1f)) { move(-60) }
                    PhoneButton("Maintenant", primary = true, modifier = Modifier.weight(1.3f)) { windowStart = floorTo30(nowMillis()) - HALF_HOUR }
                    PhoneButton("1 h  ▶", modifier = Modifier.weight(1f)) { move(60) }
                }
            }
            Text(dayLabel(windowStart), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            // The time ruler, and a mark for now.
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Spacer(Modifier.width((LOGO_COL + 4).dp))
                Box(Modifier.width((WINDOW_MIN * dpPerMin).dp).height(18.dp)) {
                    for (i in 0 until WINDOW_MIN / 30) {
                        Text(hm(windowStart + i * HALF_HOUR), color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.offset(x = (i * 30 * dpPerMin).dp))
                    }
                    val t = nowMillis()
                    if (t in windowStart until windowEnd) {
                        Box(Modifier.offset(x = ((t - windowStart) / 60_000f * dpPerMin).dp).width(2.dp).height(16.dp).background(Palette.Accent))
                    }
                }
            }
            val list = channels
            when {
                list == null -> Centered { CircularProgressIndicator() }
                list.isEmpty() -> Text(if (group == Groups.FAVORITES) "Aucune chaîne en favori : ajoute-en avec ⋮ dans l'onglet TV." else "Ce groupe est vide.",
                    color = Palette.TextDim, modifier = Modifier.padding(16.dp))
                else -> LazyColumn(state = rememberList("guide:$group", ready = true), verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                    items(list, key = { it.ch.id }) { row ->
                        GuideRow(core, row, windowStart, windowEnd, epgVersion, dpPerMin,
                            onChannel = { scope.launch { playChannel(core, player, row, null) } },
                            onProgram = { pr -> details = row to pr })
                    }
                }
            }
        }
    }

    if (chooseGroup) Options("Groupe", null, (listOf(Groups.ALL, Groups.FAVORITES) + groups).map { g ->
        Option(label(g).replaceFirstChar { it.uppercase() }, g == group) { group = g; memory.values["guide"] = g; memory.forget("guide:$g") }
    }) { chooseGroup = false }

    details?.let { (row, pr) ->
        val t = nowMillis()
        val live = pr.start <= t && pr.stop > t
        val catchup = row.ch.catchupDays > 0
        Options(pr.title, "${row.ch.name} · ${dayLabel(pr.start)} ${hm(pr.start)} – ${hm(pr.stop)}\n${pr.description ?: "Pas de description."}", listOfNotNull(
            if (live) Option("Regarder en direct") { scope.launch { playChannel(core, player, row, pr.title) } } else null,
            if (live && catchup) Option("Regarder depuis le début") { scope.launch { playCatchup(core, player, row, pr) } } else null,
            if (pr.stop <= t && catchup) Option("Regarder en rattrapage") { scope.launch { playCatchup(core, player, row, pr) } } else null,
            Option("Tout le programme de la chaîne") { open(Route.GuideChannel(row)) },
        )) { details = null }
    }
}

/** A programme already aired (or on now, from its start), from the channel's catch-up. */
internal suspend fun playCatchup(core: AppCore, player: VideoPlayer, c: ChannelRow, pr: ProgramEntity) {
    if (!Parental.allow(core, c)) return
    core.resolver.catchup(c.ch, pr)?.let { spec ->
        play(core, player, spec, pr.title, c.ch.name + " · " + dayLabel(pr.start) + " " + hm(pr.start), 0, c.ch.playlistId) { _, _ -> }
    }
}

@Composable
private fun GuideRow(core: AppCore, row: ChannelRow, windowStart: Long, windowEnd: Long, epgVersion: Int, dpPerMin: Float,
                     onChannel: () -> Unit, onProgram: (ProgramEntity) -> Unit) {
    val programs by produceState<List<ProgramEntity>?>(null, row.ch.id, windowStart, epgVersion) {
        value = runCatching { core.epg.programs(row, windowStart, windowEnd) }.getOrDefault(emptyList())
    }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(LOGO_COL.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Palette.Panel).clickable(onClick = onChannel),
            contentAlignment = Alignment.Center) {
            Text(row.ch.name.take(3), color = Palette.TextDim, fontSize = 11.sp)
            AsyncImage(row.ch.logo, row.ch.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
        }
        Spacer(Modifier.width(4.dp))
        val list = programs.orEmpty()
        if (list.isEmpty()) Cell(null, WINDOW_MIN * dpPerMin, row) {}
        else {
            var cursor = windowStart
            list.forEach { pr ->
                val s = maxOf(pr.start, windowStart)
                val e = minOf(pr.stop, windowEnd)
                if (s > cursor) Spacer(Modifier.width(((s - cursor) / 60_000f * dpPerMin).dp))
                Cell(pr, ((e - s) / 60_000f * dpPerMin).coerceAtLeast(6f), row) { onProgram(pr) }
                cursor = e
            }
        }
    }
}

@Composable
private fun Cell(pr: ProgramEntity?, widthDp: Float, row: ChannelRow, onClick: () -> Unit) {
    val now = nowMillis()
    val airing = pr != null && pr.start <= now && pr.stop > now
    val past = pr != null && pr.stop <= now
    Box(Modifier.width(widthDp.dp).fillMaxHeight().padding(end = 2.dp).clip(RoundedCornerShape(6.dp))
        .background(if (airing) Palette.PanelHigh else Palette.Panel).clickable(enabled = pr != null, onClick = onClick)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(pr?.title ?: "Aucune information", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (airing) FontWeight.SemiBold else FontWeight.Normal,
                color = if (pr == null || (past && row.ch.catchupDays == 0)) Palette.TextDim else Palette.Text)
            if (pr != null && widthDp > 60) Text(hm(pr.start) + if (past && row.ch.catchupDays > 0) "  ⟲" else "", fontSize = 11.sp, color = Palette.TextDim, maxLines = 1)
        }
        if (airing) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(((now - pr!!.start).toFloat() / (pr.stop - pr.start)).coerceIn(0f, 1f))
            .height(2.dp).background(Palette.Accent))
    }
}
