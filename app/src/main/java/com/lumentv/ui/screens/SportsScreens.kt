@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lumentv.ui.screens

import com.lumentv.ui.components.panelWidth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.data.repo.Groups
import com.lumentv.data.sports.SportEvent
import com.lumentv.data.sports.SportPrefs
import com.lumentv.data.sports.SportsCatalog
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.Screen
import com.lumentv.ui.components.Badge
import com.lumentv.ui.components.EmptyState
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.Loading
import com.lumentv.ui.components.Logo
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.ProgressLine
import com.lumentv.ui.components.ScreenTitle
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.components.TvTextField
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.components.ignoreHeldOk
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SportsScreen(vm: MainViewModel) {
    val active by vm.active.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val epgVersion by vm.graph.epg.version.collectAsStateWithLifecycle()
    val p = active ?: return EmptyState("Aucune playlist", "")
    val prefs = SportPrefs.of(settings)
    // Same system as the PC: the official calendar.
    return OfficialSports(vm, p.id, prefs)
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val events by produceState<List<SportEvent>?>(null, p.id, prefs, epgVersion, tick) { value = vm.graph.sports.events(p.id, prefs) }
    var open by remember { mutableStateOf<SportEvent?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Sports") { TvButton("Mes sports et équipes", { vm.open(Screen.SportsSettings) }) }
        Text(describe(prefs), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 2, modifier = Modifier.padding(bottom = 10.dp))
        val list = events
        when {
            p.lastEpgRefresh == 0L -> EmptyState("Pas de guide TV", "Le panneau sportif lit les programmes du guide. Actualisez le guide dans Paramètres.")
            list == null -> Loading("Recherche des matchs dans le guide…")
            list.isEmpty() -> EmptyState("Aucun match trouvé", "Rien dans le guide pour vos sports, compétitions et équipes sur ${prefs.days} jour(s). " +
                "Élargissez vos choix dans « Mes sports et équipes ».")
            else -> {
                val now = System.currentTimeMillis()
                val live = list.filter { it.isLive(now) }
                val upcoming = list.filter { !it.isLive(now) }.groupBy { dayLabel(it.start) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                    if (live.isNotEmpty()) {
                        item { DayHeader("🔴 En cours (${live.size})") }
                        items(live, key = { "l" + it.key }) { e -> EventRow(e) { open = e } }
                    }
                    upcoming.forEach { (day, evs) ->
                        item(key = "h$day") { DayHeader("$day (${evs.size})") }
                        items(evs, key = { "u" + it.key }) { e -> EventRow(e) { open = e } }
                    }
                }
            }
        }
    }
    open?.let { e -> EventDialog(vm, e, onDismiss = { open = null }) }
}

private fun describe(p: SportPrefs): String {
    val sports = if (p.sports.isEmpty()) "tous les sports" else p.sports.mapNotNull { SportsCatalog.byId(it)?.label }.joinToString(", ")
    val focus = (p.competitions + p.teams).takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { " · uniquement : $it" } ?: ""
    return "Matchs en cours et à venir sur ${p.days} jour(s) : $sports$focus. Source : guide TV de votre playlist."
}

@Composable
private fun DayHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

@Composable
private fun EventRow(e: SportEvent, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    FocusSurface(onClick, Modifier.fillMaxWidth(), focusedScale = 1.01f) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e.sport.emoji, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(44.dp))
            Column(Modifier.width(110.dp)) {
                Text(timeRange(e.start, e.stop).substringBefore(" –"), style = MaterialTheme.typography.titleMedium)
                if (e.isLive(now)) ProgressLine(progressOf(e.start, e.stop), Modifier.fillMaxWidth(0.8f).padding(top = 4.dp), Palette.Danger)
            }
            Column(Modifier.weight(1f)) {
                Text(e.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(e.sport.label, e.competition).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (e.isLive(now)) { Badge("EN DIRECT", Palette.Danger); Spacer(Modifier.width(10.dp)) }
            Text("${e.channels.size} chaîne" + if (e.channels.size > 1) "s" else "", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The channels showing a match; OK plays it live. Upcoming matches can be reminded or recorded. */
@Composable
private fun EventDialog(vm: MainViewModel, e: SportEvent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.ignoreHeldOk().panelWidth(600.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(20.dp)) {
            Text("${e.sport.emoji}  ${listOfNotNull(e.sport.label, e.competition).joinToString(" · ")}", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
            Text(e.label, style = MaterialTheme.typography.headlineSmall, color = Palette.Text, maxLines = 2)
            Text("${dayLabel(e.start)} ${timeRange(e.start, e.stop)}" + if (e.isLive(now)) " · en direct" else "",
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            e.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)) }
            Text(if (e.isLive(now)) "Choisissez une chaîne pour regarder :" else "Diffusé sur :", style = MaterialTheme.typography.titleMedium,
                color = Palette.Text, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 300.dp)) {
                items(e.channels, key = { it.ch.id }) { row ->
                    FocusSurface({ PlayerLauncher.live(context, row.ch.id, Groups.ALL); onDismiss() }, Modifier.fillMaxWidth(),
                        container = Palette.PanelHigh, focusedScale = 1.01f) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.ch.number.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp))
                            Logo(row.ch.logo, row.ch.name, size = 34.dp)
                            Text("  " + row.ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (row.ch.groupName.isNotBlank()) Text(row.ch.groupName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SportsSettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sports = remember { mutableStateListOf<String>().apply { addAll(settings.sportIds) } }
    val comps = remember { mutableStateListOf<String>().apply { addAll(settings.sportCompetitions) } }
    val teams = remember { mutableStateListOf<String>().apply { addAll(settings.sportTeams) } }
    var days by remember { mutableStateOf(settings.sportDays) }
    val leagues = remember { mutableStateListOf<String>().apply { addAll(settings.sportLeagues) } }
    var newComp by remember { mutableStateOf("") }
    var newTeam by remember { mutableStateOf("") }
    var daysMenu by remember { mutableStateOf(false) }
    val suggestions by produceState(emptyList<String>() to emptyList<String>(), active?.id, sports.toList()) {
        active?.let { value = vm.graph.sports.suggestions(it.id, sports.toSet()) }
    }
    fun save() = scope.launch {
        vm.graph.sports.save(SportPrefs(sports.toSet(), comps.toList(), teams.toList(), days, leagues.toSet()))
        vm.show("Préférences sportives enregistrées"); vm.back()
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenTitle("Mes sports et équipes") { TvButton("Enregistrer", { save() }, primary = true) } }
        item { Section("Calendrier officiel : matchs en cours et à venir, sans les rediffusions") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.lumentv.data.sports.FixtureClient.leagues.forEach { l ->
                    Chip((SportsCatalog.byId(l.sport)?.emoji?.let { "$it " } ?: "") + l.label, l.id in leagues) { if (l.id in leagues) leagues.remove(l.id) else leagues.add(l.id) }
                }
            }
        }
        item {
            Text("Le panneau Sports montre les vrais matchs de ces compétitions (calendriers publics ESPN et TheSportsDB), avec les chaînes " +
                "de votre liste qui les diffusent, ou le diffuseur habituel. Ce choix est partagé avec le PC. Les sections ci-dessous servent " +
                "seulement à filtrer par équipe et à choisir la période.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        item { Section("Sports (aucun coché = tous)") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SportsCatalog.all.forEach { s ->
                    Chip("${s.emoji} ${s.label}", s.id in sports) { if (s.id in sports) sports.remove(s.id) else sports.add(s.id) }
                }
            }
        }
        item { Section("Compétitions suivies (aucune = toutes)") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (comps + suggestions.first.filter { it !in comps }).forEach { c -> Chip(c, c in comps) { if (c in comps) comps.remove(c) else comps.add(c) } }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                TvTextField("Ajouter une compétition", newComp, { newComp = it }, Modifier.weight(1f), placeholder = "ex. Ligue 1, NBA, Top 14", keyboardType = KeyboardType.Text)
                TvButton("Ajouter", { newComp.trim().takeIf { it.isNotEmpty() && it !in comps }?.let { comps.add(it) }; newComp = "" })
            }
        }
        item { Section("Équipes / joueurs suivis (aucun = tous)") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (teams + suggestions.second.filter { it !in teams }).forEach { t -> Chip(t, t in teams) { if (t in teams) teams.remove(t) else teams.add(t) } }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                TvTextField("Ajouter une équipe ou un joueur", newTeam, { newTeam = it }, Modifier.weight(1f), placeholder = "ex. PSG, Toulouse, Sinner", keyboardType = KeyboardType.Text)
                TvButton("Ajouter", { newTeam.trim().takeIf { it.isNotEmpty() && it !in teams }?.let { teams.add(it) }; newTeam = "" })
            }
        }
        item { Section("Période") }
        item { TvButton("Afficher les matchs sur $days jour(s)", { daysMenu = true }) }
        item {
            Text("Les suggestions viennent du guide TV de la playlist active. Un match est retenu s'il correspond à un sport coché et, " +
                "si vous en avez choisi, à une compétition ou une équipe.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                modifier = Modifier.padding(top = 8.dp, bottom = 30.dp))
        }
    }
    if (daysMenu) OptionsDialog("Période", listOf(1, 2, 3, 5, 7).map { d -> MenuOption("$d jour(s)", d == days) { days = d } }, onDismiss = { daysMenu = false })
}

@Composable
private fun Section(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.Accent, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick, selected = selected, shape = RoundedCornerShape(20.dp), focusedScale = 1.05f) {
        Text((if (selected) "✓ " else "") + label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
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
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5 * 60_000L); tick++ } }
    val funnel = com.lumentv.data.sports.SportsFunnel
    val sports = prefs.sports.filter { s -> funnel.sports.any { it.id == s } }.toSet()
    val days = funnel.days(prefs.days)
    val loadKey = Triple(sports, prefs.leagues, days)
    val games by produceState<List<com.lumentv.data.sports.SportsRepository.Game>?>(null, pid, loadKey, tick) {
        value = null
        value = if (sports.isEmpty()) emptyList() else runCatching { vm.graph.sports.games(pid, prefs) }.getOrDefault(emptyList())
    }
    var open by remember { mutableStateOf<com.lumentv.data.sports.SportsRepository.Game?>(null) }
    fun save(s: Set<String> = sports, l: Set<String> = prefs.leagues, t: List<String> = prefs.teams, d: Int = days) = scope.launch {
        vm.graph.sports.save(SportPrefs(s, prefs.competitions, t, d, l))
    }
    val all = games.orEmpty()
    val clubs = remember(all) { funnel.clubs(all.map { it.fixture }) }
    val shownFixtures = remember(all, prefs.teams) { funnel.byClubs(all.map { it.fixture }, prefs.teams).toSet() }
    val shown = all.filter { it.fixture in shownFixtures }

    val phone = com.lumentv.ui.LocalPhone.current
    // Phone: the filters fold behind one summary line, so the games come first.
    var filtersOpen by remember { mutableStateOf(false) }
    val showFilters = !phone || filtersOpen || sports.isEmpty()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        if (!phone) item { ScreenTitle("Sports") }
        if (phone && sports.isNotEmpty()) item {
            val summary = listOf(funnel.sports.filter { it.id in sports }.joinToString(", ") { "${it.emoji} ${it.label}" }, "$days jours") +
                listOfNotNull(prefs.teams.takeIf { it.isNotEmpty() }?.let { "${it.size} club(s)" })
            FocusSurface({ filtersOpen = !filtersOpen }, Modifier.fillMaxWidth(), container = Palette.PanelHigh) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Filtres", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                        Text(summary.joinToString(" · "), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (filtersOpen) "Fermer  ▴" else "Modifier  ▾", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
                }
            }
        }
        // 1. Sports
        if (showFilters) item { FilterLabel("1. Sport") }
        if (showFilters) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                funnel.sports.forEach { s -> Chip("${s.emoji} ${s.label}", s.id in sports) { save(s = if (s.id in sports) sports - s.id else sports + s.id) } }
            }
        }
        if (sports.isNotEmpty() && showFilters) {
            // 2. Competitions of those sports (none = all)
            item { FilterLabel("2. Compétition (aucune cochée = toutes)") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    funnel.leaguesOf(sports).forEach { l -> Chip(l.label, l.id in prefs.leagues) { save(l = if (l.id in prefs.leagues) prefs.leagues - l.id else prefs.leagues + l.id) } }
                }
            }
            // 3. How many days ahead
            item { FilterLabel("3. Période") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    funnel.DAY_CHOICES.forEach { n -> Chip("$n jours", n == days) { if (n != days) save(d = n) } }
                }
            }
            // 4. Clubs among the listed games (none = all)
            if (clubs.isNotEmpty()) {
                item { FilterLabel("4. Club / équipe (aucun coché = tous)") }
                item {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(clubs, key = { it }) { c -> Chip(c, c in prefs.teams) { save(t = if (c in prefs.teams) prefs.teams - c else prefs.teams + c) } }
                    }
                }
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
                if (f.live) Badge("EN DIRECT", Palette.Danger)
                else Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(f.start)), style = MaterialTheme.typography.titleLarge)
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
                if (f.live) Badge("EN DIRECT", Palette.Danger)
                else Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(f.start)), style = MaterialTheme.typography.titleLarge)
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
