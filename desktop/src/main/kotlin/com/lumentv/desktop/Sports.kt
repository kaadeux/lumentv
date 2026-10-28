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
import androidx.compose.foundation.layout.width
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
    val funnel = com.lumentv.data.sports.SportsFunnel
    // Same settings as the TV (shared by the profile): sports, then competitions, then clubs.
    fun set(key: String) = state.setting(key).split('\n').filter { it.isNotBlank() }
    val sports = set("sport_ids").filter { s -> funnel.sports.any { it.id == s } }.toSet()
    val leagues = set("sport_leagues").toSet()
    val teams = set("sport_teams")
    val chosen = funnel.effectiveLeagues(sports, leagues).map { it.id }.toSet()
    val days = funnel.days(state.setting("sport_days").toIntOrNull())
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5 * 60_000L); tick++ } }
    // Every minute, so the channels unlock on screen one hour before kick-off.
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000L); value = System.currentTimeMillis() } }
    // The guide arriving later only adds channels: the games already shown stay on screen meanwhile.
    val games by produceState<List<Game>?>(null, chosen, days, tick, c, state.guide) {
        val t0 = System.currentTimeMillis()
        value = if (chosen.isEmpty()) emptyList() else runCatching { load(state, c, chosen, days) }.getOrDefault(emptyList())
        System.getenv("LUMEN_TRACE")?.let { java.io.File(it).appendText("sports ${value?.size} games in ${System.currentTimeMillis() - t0} ms (guide ${state.guide.size} channels)\n") }
    }
    fun save(key: String, values: Collection<String>) = state.setSetting(key, values.sorted().joinToString("\n"))
    val all = games.orEmpty()
    val clubs = remember(all) { funnel.clubs(all.map { it.f }) }
    val kept = remember(all, teams) { funnel.byClubs(all.map { it.f }, teams).toSet() }
    val shown = all.filter { it.f in kept }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Sports", style = MaterialTheme.typography.headlineSmall, color = Pal.text) }
        item {
            Text("Matchs en cours et à venir (calendrier officiel, sans les rediffusions), avec les chaînes qui les diffusent.", color = Pal.dim)
        }
        item { Step("1. Sport") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                funnel.sports.forEach { sp -> ChoiceChip("${sp.emoji}  ${sp.label}", sp.id in sports) { save("sport_ids", if (sp.id in sports) sports - sp.id else sports + sp.id) } }
            }
        }
        if (sports.isNotEmpty()) {
            item { Step("2. Compétition  ·  aucune cochée = toutes") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    funnel.leaguesOf(sports).forEach { l -> ChoiceChip(l.label, l.id in leagues) { save("sport_leagues", if (l.id in leagues) leagues - l.id else leagues + l.id) } }
                }
            }
            item { Step("3. Période") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    funnel.DAY_CHOICES.forEach { n -> ChoiceChip("$n jours", n == days) { state.setSetting("sport_days", n.toString()) } }
                }
            }
            if (clubs.isNotEmpty()) {
                item { Step("4. Club / équipe  ·  aucun coché = tous") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        clubs.forEach { cl -> ChoiceChip(cl, cl in teams, small = true) { save("sport_teams", if (cl in teams) teams - cl else teams + cl) } }
                    }
                }
            }
        }
        when {
            sports.isEmpty() -> item { Empty("Choisissez un sport", "Puis, si vous voulez, une compétition et un club. Le choix est partagé avec la TV.") }
            games == null -> item { Text("Recherche des matchs…", color = Pal.dim, modifier = Modifier.padding(16.dp)) }
            shown.isEmpty() -> item { Empty("Aucun match", "Pas de match en cours ni à venir sur $days jours avec ces filtres.") }
            else -> {
                shown.groupBy { g -> if (g.f.live) "En direct" else dayLabel(g.f.start) }.forEach { (label, list) ->
                    item(key = "h$label") {
                        Text(label, color = if (label == "En direct") liveRed else Pal.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    }
                    items(list, key = { it.f.id }) { g -> GameCard(state, g, now) }
                }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun Step(text: String) = Text(text, color = Pal.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun ChoiceChip(label: String, on: Boolean, small: Boolean = false, onClick: () -> Unit) =
    Text(label, color = if (on) Color.White else Pal.dim, fontSize = if (small) 12.sp else 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Pal.accent else Pal.panelHigh)
            .combinedClickable(onClick = onClick).padding(horizontal = if (small) 11.dp else 14.dp, vertical = if (small) 5.dp else 7.dp))

@Composable
private fun GameCard(state: AppState, g: Game, now: Long) {
    val f = g.f
    val hm = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.panel).border(1.dp, if (f.live) liveRed.copy(alpha = 0.5f) else Pal.panelHigh, RoundedCornerShape(14.dp))
        .padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // "⚽ FOOTBALL · LIGUE 1 · JOURNÉE 10 · Stade Océane, Le Havre"
        Text(listOfNotNull(f.details.uppercase(), f.venue).joinToString("  ·  "), color = Pal.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // As on the TV: the time on the left, then the two teams around "vs".
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(120.dp)) {
                if (f.live) {
                    Text("● EN DIRECT", color = liveRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (f.detail.isNotBlank()) Text(f.detail, color = liveRed, fontSize = 13.sp)
                } else Text(hm.format(Date(f.start)), color = Pal.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            if (f.home != null && f.away != null) {
                TeamCell(f.home!!, Modifier.weight(1f), end = true)
                Text(if (f.live && f.homeScore != null) "${f.homeScore}  –  ${f.awayScore}" else "vs", color = if (f.live) Pal.text else Pal.dim,
                    fontSize = if (f.live) 24.sp else 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                TeamCell(f.away!!, Modifier.weight(1f), end = false)
            } else Text(f.name, color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        val tvNote = g.tv?.let { l ->
            "Diffusé sur ${l.channel}" + if (kotlin.math.abs(l.start - f.start) >= 90 * 60_000L) " à " + SimpleDateFormat("HH:mm EEEE", Locale.FRANCE).format(Date(l.start)) else ""
        }
        if (g.channels.isEmpty()) Text(tvNote?.let { "$it — cette chaîne n'est pas dans votre liste" }
            ?: listOfNotNull(f.usualBroadcaster, "aucune chaîne de votre liste ne l'annonce encore").joinToString(" · ").replaceFirstChar { it.uppercase() },
            color = Pal.dim, fontSize = 13.sp)
        else {
            val open = f.channelsOpen(now)
            listOfNotNull(tvNote, f.opensNote.takeUnless { open }).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString("  ·  "), color = Pal.dim, fontSize = 13.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                g.channels.take(8).forEach { ch -> Pill(ch.name, { if (f.channelsOpen()) state.playChannel(ch) }, Icons.Filled.PlayArrow, primary = f.live, enabled = open) }
            }
        }
    }
}

@Composable
private fun TeamCell(t: Team, modifier: Modifier, end: Boolean) = Row(modifier, verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
    // The name gives way, not the crest.
    if (end) Text(t.name, color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp))
    AsyncImage(t.logo, null, Modifier.size(42.dp), contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
    if (!end) Text(t.name, color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp))
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

