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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
    // Again every 30 s while a game is live (score, minute, half-time), every 5 min otherwise.
    var live by remember { mutableStateOf(false) }
    LaunchedEffect(live) { while (true) { delay(if (live) FixtureClient.LIVE_REFRESH_MS else 5 * 60_000L); tick++ } }
    // Every minute, so the channels unlock on screen one hour before kick-off.
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000L); value = System.currentTimeMillis() } }
    // The guide arriving later only adds channels: the games already shown stay on screen meanwhile.
    val games by produceState<List<Game>?>(null, chosen, days, tick, c, state.guide) {
        val t0 = System.currentTimeMillis()
        value = if (chosen.isEmpty()) emptyList() else runCatching { load(state, c, chosen, days) }.getOrElse { value ?: emptyList() }
        live = FixtureClient.hasLive(value.orEmpty().map { it.f })
        System.getenv("LUMEN_TRACE")?.let { java.io.File(it).appendText("sports ${value?.size} games in ${System.currentTimeMillis() - t0} ms (guide ${state.guide.size} channels)\n") }
    }
    fun save(key: String, values: Collection<String>) = state.setSetting(key, values.sorted().joinToString("\n"))
    val all = games.orEmpty()
    val clubs = remember(all) { funnel.clubs(all.map { it.f }) }
    val kept = remember(all, teams) { funnel.byClubs(all.map { it.f }, teams).toSet() }
    val shown = all.filter { it.f in kept }
    val leagueList = funnel.leaguesOf(sports)
    var picker by remember { mutableStateOf<String?>(null) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Sports", style = MaterialTheme.typography.headlineSmall, color = Pal.text) }
        item {
            Text("Matchs en cours et à venir (calendrier officiel, sans les rediffusions), avec les chaînes qui les diffusent.", color = Pal.dim)
        }
        // The sports as chips (the main choice), then the rest as three menus on one line.
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                funnel.sports.forEach { sp -> ChoiceChip("${sp.emoji}  ${sp.label}", sp.id in sports) { save("sport_ids", if (sp.id in sports) sports - sp.id else sports + sp.id) } }
            }
        }
        if (sports.isNotEmpty()) item {
            val comps = leagueList.filter { it.id in leagues }.map { it.label }
            val picked = teams.filter { it in clubs }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Menu("Compétition", funnel.summary(comps, "Toutes", "compétitions"), comps.isNotEmpty(), picker == "leagues", { picker = if (it) "leagues" else null }) {
                    MenuEntry(if (comps.isEmpty()) "✓  Toutes" else "     Toutes") { save("sport_leagues", leagues - leagueList.map { it.id }.toSet()) }
                    leagueList.forEach { l ->
                        val on = l.id in leagues
                        MenuEntry((if (on) "✓  " else "     ") + (if (sports.size > 1) SportsCatalog.byId(l.sport)?.emoji?.let { "$it  " }.orEmpty() else "") + l.label) {
                            save("sport_leagues", if (on) leagues - l.id else leagues + l.id)
                        }
                    }
                }
                if (clubs.isNotEmpty() || picked.isNotEmpty()) Menu("Équipe", funnel.summary(picked, "Toutes", "équipes"), picked.isNotEmpty(), picker == "clubs", { picker = if (it) "clubs" else null }) {
                    MenuEntry(if (picked.isEmpty()) "✓  Tous les matchs" else "     Tous les matchs") { save("sport_teams", emptyList()) }
                    clubs.forEach { cl ->
                        val on = cl in teams
                        MenuEntry((if (on) "✓  " else "     ") + cl) { save("sport_teams", if (on) teams - cl else teams + cl) }
                    }
                }
                Menu("Période", "$days jours", false, picker == "days", { picker = if (it) "days" else null }) {
                    funnel.DAY_CHOICES.forEach { n -> MenuEntry((if (n == days) "✓  " else "     ") + "$n jours", close = true) { state.setSetting("sport_days", n.toString()); picker = null } }
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

/** A filter as one button ("Compétition · Toutes ▾") opening a list; ticking leaves it open to tick several. */
@Composable
private fun Menu(title: String, value: String, active: Boolean, open: Boolean, onOpen: (Boolean) -> Unit, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box {
        Row(Modifier.clip(RoundedCornerShape(50)).background(if (active) Pal.accent.copy(alpha = 0.25f) else Pal.panelHigh)
            .combinedClickable { onOpen(!open) }.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title · ", color = Pal.dim, fontSize = 13.sp)
            Text(value, color = Pal.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            Icon(Icons.Filled.ArrowDropDown, null, tint = Pal.dim, modifier = Modifier.size(18.dp))
        }
        androidx.compose.material3.DropdownMenu(open, { onOpen(false) }, containerColor = Pal.panel, modifier = Modifier.heightIn(max = 420.dp)) { content() }
    }
}

@Composable
private fun MenuEntry(label: String, close: Boolean = false, onClick: () -> Unit) =
    androidx.compose.material3.DropdownMenuItem({ Text(label, color = Pal.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick)

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
                    Text(f.clock, color = liveRed, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
            // The French guide's broadcaster first; the playlist's own guide only when it says nothing.
            val own = (progs.filter { (_, title, start) -> FixtureMatcher.matches(f, title, start) } +
                named.filter { (_, title, start) -> FixtureMatcher.matches(f, title, start, channelName = true) }).map { it.first }
            val sportKeys = listings.mapTo(HashSet()) { com.lumentv.data.sports.FrenchTvGuide.channelKey(it.channel) }
            Game(f, (if (linked.isNotEmpty()) linked else own.filter { sportKeys.isEmpty() || com.lumentv.data.sports.FrenchTvGuide.channelKey(it.name) in sportKeys }).distinctBy { it.uid }
                .filter { com.lumentv.data.sports.FrenchTvGuide.french(it.name, it.group) }, tv)
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

