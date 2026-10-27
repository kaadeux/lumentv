@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.lumentv.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.core.normalize
import com.lumentv.data.sports.EventChannelParser
import com.lumentv.data.sports.Fixture
import com.lumentv.data.sports.FixtureClient
import com.lumentv.data.sports.FixtureMatcher
import com.lumentv.data.sports.SportsCatalog
import com.lumentv.data.sports.Team
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val liveRed = Color(0xFFFF5A5F)

/** A game of the official calendar, the channels of the playlist that show it, and the French channel (xmltvfr.fr). */
private class Game(val f: Fixture, val channels: List<Ch>, val tv: com.lumentv.data.sports.Listing? = null)

/**
 * Sports: live and upcoming games of the chosen competitions (official calendar, no replays), each with the
 * channels whose guide or name announces it. The choice is shared with the TV through the profile.
 */
@Composable
fun SportsScreen(state: AppState) {
    val c = state.catalog!!
    // LUMEN_SPORTS=fra1,top14 shows those competitions without saving them (screenshots).
    val chosen = System.getenv("LUMEN_SPORTS")?.split(',')?.toSet() ?: state.setting("sport_leagues").split('\n').filter { it.isNotBlank() }.toSet()
    val days = state.setting("sport_days", "7").toIntOrNull() ?: 7
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5 * 60_000L); tick++ } }
    val games by produceState<List<Game>?>(null, chosen, days, tick, c, state.guide) {
        value = if (chosen.isEmpty()) emptyList() else runCatching { load(state, c, chosen, days) }.getOrDefault(emptyList())
    }
    fun toggle(id: String) = state.setSetting("sport_leagues", (if (id in chosen) chosen - id else chosen + id).sorted().joinToString("\n"))

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Sports", style = MaterialTheme.typography.headlineSmall, color = Pal.text) }
        item {
            Text("Matchs en cours et à venir du calendrier officiel, sans les rediffusions, avec les chaînes de votre liste qui les diffusent.",
                color = Pal.dim)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FixtureClient.leagues.forEach { l ->
                    val on = l.id in chosen
                    Text((SportsCatalog.byId(l.sport)?.emoji?.let { "$it  " } ?: "") + l.label, color = if (on) Color.White else Pal.dim, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Pal.accent else Pal.panelHigh)
                            .combinedClickable { toggle(l.id) }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
        when {
            chosen.isEmpty() -> item { Empty("Choisissez vos compétitions", "Cochez par exemple Ligue 1, Top 14 ou Ligue des champions ci-dessus.") }
            games == null -> item { Text("Recherche des matchs…", color = Pal.dim, modifier = Modifier.padding(16.dp)) }
            games!!.isEmpty() -> item { Empty("Aucun match", "Pas de match en cours ni à venir sur $days jour(s) dans ces compétitions.") }
            else -> {
                val byDay = games!!.groupBy { g -> if (g.f.live) "En direct" else dayLabel(g.f.start) }
                byDay.forEach { (label, list) ->
                    item(key = "h$label") {
                        Text(label, color = if (label == "En direct") liveRed else Pal.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    }
                    items(list, key = { it.f.id }) { g -> GameCard(state, g) }
                }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun GameCard(state: AppState, g: Game) {
    val f = g.f
    val hm = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.panel).border(1.dp, if (f.live) liveRed.copy(alpha = 0.5f) else Pal.panelHigh, RoundedCornerShape(14.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(f.details.uppercase(), color = Pal.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (f.live) Text("● EN DIRECT" + if (f.detail.isNotBlank()) " · ${f.detail}" else "", color = liveRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            else Text(hm.format(Date(f.start)), color = Pal.text, fontWeight = FontWeight.SemiBold)
        }
        if (f.home != null && f.away != null) Row(verticalAlignment = Alignment.CenterVertically) {
            TeamCell(f.home!!, Modifier.weight(1f), end = false)
            Text(if (f.live && f.homeScore != null) "${f.homeScore}  –  ${f.awayScore}" else "vs", color = if (f.live) Pal.text else Pal.dim,
                fontSize = if (f.live) 22.sp else 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
            TeamCell(f.away!!, Modifier.weight(1f), end = true)
        } else Text(f.name, color = Pal.text, fontWeight = FontWeight.SemiBold)
        f.venue?.let { Text(it, color = Pal.dim, fontSize = 12.sp) }
        val tvNote = g.tv?.let { l ->
            "Diffusé sur ${l.channel}" + if (kotlin.math.abs(l.start - f.start) >= 90 * 60_000L) " à " + SimpleDateFormat("HH:mm EEEE", Locale.FRANCE).format(Date(l.start)) else ""
        }
        if (g.channels.isEmpty()) Text(tvNote?.let { "$it — cette chaîne n'est pas dans votre liste" }
            ?: listOfNotNull(f.usualBroadcaster, "aucune chaîne de votre liste ne l'annonce encore").joinToString(" · ").replaceFirstChar { it.uppercase() },
            color = Pal.dim, fontSize = 12.sp)
        else {
            tvNote?.let { Text(it, color = Pal.dim, fontSize = 12.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                g.channels.take(8).forEach { ch -> Pill(ch.name, { state.playChannel(ch) }, Icons.Filled.PlayArrow, primary = f.live) }
            }
        }
    }
}

@Composable
private fun TeamCell(t: Team, modifier: Modifier, end: Boolean) = Row(modifier, verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
    if (end) Text(t.name, color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 10.dp))
    AsyncImage(t.logo, null, Modifier.size(36.dp), contentScale = ContentScale.Fit)
    if (!end) Text(t.name, color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
}

private suspend fun load(state: AppState, c: Catalog, chosen: Set<String>, days: Int): List<Game> {
    val games = state.fixtures.fixtures(chosen.mapNotNull { FixtureClient.league(it) }, days)
    val listings = runCatching { state.frenchGuide.listings() }.getOrDefault(emptyList())
    return withContext(Dispatchers.Default) {
        // Programmes near each kick-off (normalised once), and channels whose name announces a game.
        val from = (games.minOfOrNull { it.start } ?: 0) - FixtureMatcher.BEFORE_MS
        val to = (games.maxOfOrNull { it.start } ?: 0) + FixtureMatcher.AFTER_MS
        val progs = c.channels.flatMap { ch -> state.guide[ch.uid].orEmpty().filter { it.start in from..to }.map { Triple(ch, normalize(it.title), it.start) } }
        val named = c.channels.mapNotNull { ch -> EventChannelParser.parse(ch.name)?.let { (l, t) -> Triple(ch, normalize(l), t) } }
        val byKey = c.channels.filter { com.lumentv.data.sports.FrenchTvGuide.frenchOrPlain(it.name) }.groupBy { com.lumentv.data.sports.FrenchTvGuide.channelKey(it.name) }
        games.map { f ->
            val tv = com.lumentv.data.sports.FrenchTvGuide.broadcast(f, listings)
            // The French channel, when the playlist has it under the same name, becomes playable too.
            val linked = tv?.let { com.lumentv.data.sports.FrenchTvGuide.best(byKey[com.lumentv.data.sports.FrenchTvGuide.channelKey(it.channel)].orEmpty()) { ch -> ch.name } }.orEmpty()
            Game(f, ((progs.filter { (_, title, start) -> FixtureMatcher.matches(f, title, start) } +
                named.filter { (_, title, start) -> FixtureMatcher.matches(f, title, start, channelName = true) }).map { it.first } + linked).distinctBy { it.uid }, tv)
        }
    }
}

private fun dayLabel(t: Long): String {
    val d = Calendar.getInstance().apply { timeInMillis = t }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(d, today)) return "Aujourd'hui"
    today.add(Calendar.DAY_OF_YEAR, 1)
    if (same(d, today)) return "Demain"
    return SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(t)).replaceFirstChar { it.uppercase() }
}

