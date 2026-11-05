@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lumentv.ui.screens

import androidx.compose.foundation.layout.height
import com.lumentv.ui.glassPill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.data.repo.Groups
import com.lumentv.data.sports.SportPrefs
import com.lumentv.data.sports.SportsCatalog
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.components.Badge
import com.lumentv.ui.components.EmptyState
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.Loading
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.ScreenTitle
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.components.ignoreHeldOk
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SportsScreen(vm: MainViewModel) {
    val active by vm.active.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val p = active ?: return EmptyState("Aucune playlist", "")
    val prefs = SportPrefs.of(settings)
    // Same system as the PC: the official calendar.
    OfficialSports(vm, p.id, prefs)
}

@Composable
private fun DayHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    com.lumentv.ui.GlassChip(onClick, Modifier.glassPill(RoundedCornerShape(22.dp)).height(38.dp), selected = selected, shape = RoundedCornerShape(22.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * Official calendar (same as the PC): live games first with their score, then by day; each with team logos and the
 * channels of the playlist that show it, or the usual broadcaster in France when none announces it yet.
 */
@Composable
private fun OfficialSports(vm: MainViewModel, pid: Long, prefs: SportPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val funnel = com.lumentv.data.sports.SportsFunnel
    val sports = prefs.sports.filter { s -> funnel.sports.any { it.id == s } }.toSet()
    val days = funnel.days(prefs.days)
    val loadKey = Triple(sports, prefs.leagues, days)
    // Loaded again every 30 s while a game is live (score, minute, half-time), every 5 min otherwise; the list
    // stays on screen meanwhile.
    val games by produceState<List<com.lumentv.data.sports.SportsRepository.Game>?>(null, pid, loadKey) {
        value = null
        while (true) {
            value = if (sports.isEmpty()) emptyList() else runCatching { vm.graph.sports.games(pid, prefs) }.getOrElse { value ?: emptyList() }
            val live = com.lumentv.data.sports.FixtureClient.hasLive(value.orEmpty().map { it.fixture })
            delay(if (live) com.lumentv.data.sports.FixtureClient.LIVE_REFRESH_MS else 5 * 60_000L)
        }
    }
    var open by remember { mutableStateOf<com.lumentv.data.sports.SportsRepository.Game?>(null) }
    fun save(s: Set<String> = sports, l: Set<String> = prefs.leagues, t: List<String> = prefs.teams, d: Int = days) = scope.launch {
        vm.graph.sports.save(SportPrefs(s, prefs.competitions, t, d, l))
    }
    val all = games.orEmpty()
    val clubs = remember(all) { funnel.clubs(all.map { it.fixture }) }
    val shownFixtures = remember(all, prefs.teams) { funnel.byClubs(all.map { it.fixture }, prefs.teams).toSet() }
    val shown = all.filter { it.fixture in shownFixtures }
    val leagues = funnel.leaguesOf(sports)
    var picker by remember { mutableStateOf<String?>(null) }

    val phone = com.lumentv.ui.LocalPhone.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        if (!phone) item { ScreenTitle("Sports") }
        // The sports as chips (the main choice), then the rest as three menus on one line.
        item {
            if (phone) androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(funnel.sports, key = { it.id }) { s -> Chip("${s.emoji} ${s.label}", s.id in sports) { save(s = if (s.id in sports) sports - s.id else sports + s.id) } }
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                funnel.sports.forEach { s -> Chip("${s.emoji} ${s.label}", s.id in sports) { save(s = if (s.id in sports) sports - s.id else sports + s.id) } }
            }
        }
        if (sports.isNotEmpty()) item {
            val comps = leagues.filter { it.id in prefs.leagues }.map { it.label }
            val picked = prefs.teams.filter { it in clubs }
            FlowRow(Modifier.padding(top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuButton("Compétition", funnel.summary(comps, "Toutes", "compétitions"), comps.isNotEmpty()) { picker = "leagues" }
                if (clubs.isNotEmpty() || picked.isNotEmpty()) MenuButton("Équipe", funnel.summary(picked, "Toutes", "équipes"), picked.isNotEmpty()) { picker = "clubs" }
                MenuButton("Période", "$days jours", false) { picker = "days" }
            }
        }
        when {
            sports.isEmpty() -> item { EmptyState("Choisissez un sport", "Puis, si vous voulez, une compétition et un club. Le choix est partagé avec le PC.") }
            games == null -> item { Loading("Recherche des matchs…") }
            shown.isEmpty() -> item { EmptyState("Aucun match", "Pas de match en cours ni à venir sur $days jours avec ces filtres.") }
            else -> shown.groupBy { if (it.fixture.live) "🔴 En direct" else dayLabel(it.fixture.start) }.forEach { (day, gs) ->
                item(key = "h$day") { DayHeader(day) }
                items(gs, key = { it.fixture.id }) { g ->
                    GameRow(g) {
                        if (g.channels.isNotEmpty() && !g.fixture.channelsOpen()) vm.show(g.fixture.opensNote)
                        else when (g.channels.size) {
                            0 -> vm.show(g.tv?.let { "Diffusé sur $it — cette chaîne n'est pas dans votre liste" }
                                ?: listOfNotNull(g.fixture.usualBroadcaster, "aucune chaîne de votre liste ne l'annonce encore").joinToString(" · ").replaceFirstChar { it.uppercase() })
                            1 -> PlayerLauncher.live(context, g.channels[0].ch.id, Groups.ALL)
                            else -> open = g
                        }
                    }
                }
            }
        }
    }
    open?.let { g ->
        OptionsDialog(g.fixture.label, g.channels.map { row -> MenuOption(row.ch.name) { PlayerLauncher.live(context, row.ch.id, Groups.ALL) } },
            onDismiss = { open = null }, subtitle = g.fixture.league.label + " · choisissez une chaîne")
    }
    when (picker) {
        "leagues" -> PickDialog("Compétitions", "Aucune cochée = toutes celles de vos sports",
            leagues.map { l -> l.id to ((if (sports.size > 1) SportsCatalog.byId(l.sport)?.emoji?.let { "$it  " } else null).orEmpty() + l.label) },
            prefs.leagues, onToggle = { id -> save(l = if (id in prefs.leagues) prefs.leagues - id else prefs.leagues + id) },
            onClear = { save(l = prefs.leagues - leagues.map { it.id }.toSet()) }, onDismiss = { picker = null })
        "clubs" -> PickDialog("Équipes", "Aucune cochée = tous les matchs", (clubs + prefs.teams.filter { it !in clubs }).map { it to it },
            prefs.teams.toSet(), onToggle = { c -> save(t = if (c in prefs.teams) prefs.teams - c else prefs.teams + c) },
            onClear = { save(t = emptyList()) }, onDismiss = { picker = null })
        "days" -> OptionsDialog("Période", funnel.DAY_CHOICES.map { n -> MenuOption("$n jours", n == days) { if (n != days) save(d = n) } }, onDismiss = { picker = null })
    }
}

/** A filter as one button: "Compétition · Toutes ▾". Opens its list. */
@Composable
private fun MenuButton(title: String, value: String, active: Boolean, onClick: () -> Unit) {
    com.lumentv.ui.GlassChip(onClick, Modifier.glassPill(RoundedCornerShape(22.dp)).height(38.dp), selected = active, dot = false, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title · ", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim, maxLines = 1)
            Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            Text("  ▾", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
        }
    }
}

/** A list to tick several entries in (the shared menu); it stays open until "Fermer" or Back. */
@Composable
private fun PickDialog(title: String, subtitle: String, items: List<Pair<String, String>>, selected: Set<String>,
                       onToggle: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val none = items.none { it.first in selected }
    com.lumentv.ui.kit.KitMenu(title, listOf(com.lumentv.ui.kit.KitOption(if (title == "Équipes") "Tous les matchs" else "Toutes", none, onClick = onClear)) +
        items.map { (id, label) -> com.lumentv.ui.kit.KitOption(label, id in selected) { onToggle(id) } },
        onDismiss, subtitle, closeOnPick = false, rootModifier = Modifier.ignoreHeldOk())
}

@Composable
private fun FilterLabel(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = Palette.Accent, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun GameRow(g: com.lumentv.data.sports.SportsRepository.Game, onClick: () -> Unit) {
    val f = g.fixture
    // Re-read every minute, so a game's channels unlock on screen one hour before kick-off.
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() } }
    FocusSurface(onClick, Modifier.fillMaxWidth(), focusedScale = 1.01f) {
      Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        // "⚽ Football · Ligue 1 · Journée 10 · Stade Océane, Le Havre"
        Text(listOfNotNull(f.details, f.venue).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
        if (com.lumentv.ui.LocalPhone.current) {
            // Phone: time and channel on one line, then the two teams face to face on the next.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (f.live) {
                    Badge("EN DIRECT", Palette.Danger)
                    Text("  " + f.clock, style = MaterialTheme.typography.titleMedium, color = Palette.Danger, maxLines = 1)
                } else Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(f.start)), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(12.dp))
                val ch = when {
                    g.channels.isNotEmpty() && !f.channelsOpen(now) -> g.channels.first().ch.name
                    g.channels.isNotEmpty() -> "▶ " + g.channels.first().ch.name
                    g.tv != null -> "Sur ${g.tv}"
                    else -> f.usualBroadcaster ?: "Chaîne inconnue"
                }
                // What follows the name is never cut: a long channel name gives way instead.
                val after = when {
                    g.channels.isNotEmpty() && !f.channelsOpen(now) -> " · dès " +
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(f.start - com.lumentv.data.sports.Fixture.OPEN_BEFORE_MS))
                    g.channels.size > 1 -> "  +${g.channels.size - 1}"
                    else -> null
                }
                val color = if (g.channels.isNotEmpty() && f.channelsOpen(now)) Palette.Text else Palette.TextDim
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    Text(ch, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    after?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1) }
                }
            }
            if (f.home != null && f.away != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamSide(f.home!!, Modifier.weight(1f), end = true, phone = true)
                Text(if (f.live && f.homeScore != null) "${f.homeScore} – ${f.awayScore}" else "vs", style = MaterialTheme.typography.titleMedium,
                    color = if (f.live) Palette.Text else Palette.TextDim, modifier = Modifier.padding(horizontal = 10.dp))
                TeamSide(f.away!!, Modifier.weight(1f), end = false, phone = true)
            } else Text(f.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(120.dp)) {
                if (f.live) {
                    Badge("EN DIRECT", Palette.Danger)
                    Text(f.clock, style = MaterialTheme.typography.titleMedium, color = Palette.Danger, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                } else Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(f.start)), style = MaterialTheme.typography.titleLarge)
            }
            if (f.home != null && f.away != null) {
                TeamSide(f.home!!, Modifier.weight(1f), end = true)
                Text(if (f.live && f.homeScore != null) "${f.homeScore} – ${f.awayScore}" else "vs", style = MaterialTheme.typography.titleLarge,
                    color = if (f.live) Palette.Text else Palette.TextDim, modifier = Modifier.padding(horizontal = 18.dp))
                TeamSide(f.away!!, Modifier.weight(1f), end = false)
            } else Text(f.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(2f))
            Column(Modifier.width(250.dp), horizontalAlignment = Alignment.End) {
                if (g.channels.isNotEmpty() && !f.channelsOpen(now)) {
                    // Announced, but not playable yet: opens one hour before kick-off.
                    Text(g.channels.first().ch.name, style = MaterialTheme.typography.titleSmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Accessible dès " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(f.start - com.lumentv.data.sports.Fixture.OPEN_BEFORE_MS)),
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
                } else if (g.channels.isNotEmpty()) {
                    Text("▶ " + g.channels.first().ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (g.channels.size > 1) Text("+ ${g.channels.size - 1} autre(s) chaîne(s)", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    else g.tv?.let { Text("Sur $it", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1) }
                } else if (g.tv != null) {
                    Text("Sur ${g.tv}", style = MaterialTheme.typography.titleSmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    tvTimeNote(g)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1) }
                } else Text(f.usualBroadcaster ?: "Chaîne inconnue", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2)
            }
        }
      }
    }
}

/** When the French channel starts the game at another time than the official calendar: "à 16:45 samedi". */
private fun tvTimeNote(g: com.lumentv.data.sports.SportsRepository.Game): String? {
    if (g.tvStart == 0L || kotlin.math.abs(g.tvStart - g.fixture.start) < 90 * 60_000L) return null
    return "à " + java.text.SimpleDateFormat("HH:mm EEEE", java.util.Locale.FRANCE).format(java.util.Date(g.tvStart))
}

@Composable
private fun TeamSide(t: com.lumentv.data.sports.Team, modifier: Modifier, end: Boolean, phone: Boolean = false) =
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        // The name gives way, not the crest: a long name ("Clermont Auvergne") used to squeeze the logo out.
        // Phone: smaller, and a long name wraps on two lines rather than being cut.
        val style = if (phone) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
        val lines = if (phone) 2 else 1
        val gap = if (phone) 6.dp else 10.dp
        val align = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start
        if (end) Text(t.short ?: t.name, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.weight(1f, fill = false).padding(end = gap))
        coil.compose.AsyncImage(t.logo, null, Modifier.size(if (phone) 28.dp else 36.dp))
        if (!end) Text(t.short ?: t.name, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.weight(1f, fill = false).padding(start = gap))
    }
