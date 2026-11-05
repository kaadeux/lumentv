package com.lumentv.phone

import com.lumentv.data.sports.SportsCatalog
import com.lumentv.data.sports.FixtureClient
import com.lumentv.data.sports.Fixture
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.lumentv.core.Featured
import com.lumentv.core.nowMillis
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PrefKind
import com.lumentv.data.db.ProgramEntity
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.sports.SportPrefs
import com.lumentv.data.sports.SportsFunnel
import com.lumentv.data.sports.SportsRepository
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ---- Times ------------------------------------------------------------------------------------------------------

private val zone get() = TimeZone.currentSystemDefault()
internal fun hm(t: Long): String = Instant.fromEpochMilliseconds(t).toLocalDateTime(zone).let {
    it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0')
}
private fun dateOf(t: Long): LocalDate = Instant.fromEpochMilliseconds(t).toLocalDateTime(zone).date
private val weekDays = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")
private val months = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")

/** "Aujourd'hui", "Demain", "Hier", "Samedi 8 nov.". */
internal fun dayLabel(t: Long): String {
    val d = dateOf(t)
    val today = dateOf(nowMillis())
    val diff = d.toEpochDays() - today.toEpochDays()
    return when (diff) {
        0 -> "Aujourd'hui"
        1 -> "Demain"
        -1 -> "Hier"
        else -> weekDays[d.dayOfWeek.ordinal] + " " + d.dayOfMonth + " " + months[d.monthNumber - 1]
    }
}

// ---- "Plus": the destinations that are not tabs ------------------------------------------------------------------

@Composable
internal fun MoreHome(open: (Route) -> Unit) = LazyColumn(state = rememberList("more"),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { Entry("▦", "Guide TV", "Les programmes de toutes les chaînes, et le rattrapage") { open(Route.Guide) } }
    item { Entry("★", "Favoris", "Vos chaînes, films et séries préférés") { open(Route.Favorites) } }
    item { Entry("⚽", "Sport", "Les matchs et les chaînes qui les diffusent") { open(Route.Sports) } }
    item { Entry("⬇", "Téléchargés", "Films et épisodes sur ce téléphone") { open(Route.Downloads) } }
    item { Entry("⚙", "Paramètres", "Profil en ligne, liste, lecture") { open(Route.Settings) } }
}

/** A row of "Plus" as on the Android phone: the icon in orange, the name and what it holds. */
@Composable
private fun Entry(icon: String, title: String, subtitle: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Panel).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(icon, fontSize = 24.sp, color = Palette.Accent, modifier = Modifier.width(28.dp))
    Column(Modifier.weight(1f).padding(start = 16.dp)) {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Palette.Text)
        Text(subtitle, color = Palette.TextDim, fontSize = 13.sp)
    }
    Text("›", fontSize = 22.sp, color = Palette.TextDim)
}

/** A channel's line: logo, name, what is on (and its progress). */
@Composable
internal fun ChannelLine(core: AppCore, c: ChannelRow, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val now by produceState<ProgramEntity?>(null, c.ch.id) { value = runCatching { core.epg.nowNext(c).now }.getOrNull() }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
            AsyncImage(c.ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(c.ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            now?.let { n ->
                Text(n.title, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val t = nowMillis()
                if (n.stop > n.start) ProgressLine(((t - n.start).toFloat() / (n.stop - n.start)).coerceIn(0f, 1f), Modifier.fillMaxWidth(0.6f).padding(top = 4.dp))
            }
        }
        trailing()
    }
}

/** ☆ / ★: adds the channel to Ma liste or takes it out. */
@Composable
internal fun FavoriteStar(core: AppCore, c: ChannelRow) {
    var on by remember(c.ch.id, c.isFavorite) { mutableStateOf(c.isFavorite) }
    val scope = rememberCoroutineScope()
    Text(if (on) "★" else "☆", fontSize = 22.sp, color = if (on) Palette.Gold else Palette.TextDim,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable {
            scope.launch { on = core.library.toggleFavorite(c.ch.playlistId, PrefKind.CHANNEL, c.ch.uid) }
        }.padding(10.dp))
}

// ---- Favourites -------------------------------------------------------------------------------------------------

@Composable
internal fun FavoritesPage(core: AppCore, p: PlaylistEntity, player: VideoPlayer, open: (Route) -> Unit) {
    val changes by core.library.changes.collectAsState()
    val movies by produceState(emptyList<MovieRow>(), changes) { value = core.library.favoriteMovies().filter { it.m.playlistId == p.id } }
    val series by produceState(emptyList<SeriesRow>(), changes) { value = core.library.favoriteSeries().filter { it.s.playlistId == p.id } }
    val channels by produceState(emptyList<ChannelRow>(), changes) { value = core.library.favoriteChannelsAll().filter { it.ch.playlistId == p.id } }
    val scope = rememberCoroutineScope()
    LazyColumn(state = rememberList("favorites", ready = movies.isNotEmpty() || series.isNotEmpty() || channels.isNotEmpty()), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (movies.isEmpty() && series.isEmpty() && channels.isEmpty()) item {
            Text("Rien encore. Ajoute un film ou une série avec « + Ma liste » sur sa page, une chaîne avec ☆.",
                color = Palette.TextDim, modifier = Modifier.padding(16.dp))
        }
        if (channels.isNotEmpty()) {
            item { Title("Chaînes") }
            items(channels, key = { "c" + it.ch.id }) { c ->
                ChannelLine(core, c, { scope.launch { playChannel(core, player, c, null) } }) { FavoriteStar(core, c) }
            }
        }
        if (movies.isNotEmpty()) {
            item { Title("Films") }
            item {
                LazyRow(state = rememberList("fav:mv"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(movies, key = { it.m.id }) { m -> Box(Modifier.width(110.dp)) { Poster(m.m.poster, Featured.shortTitle(m.m.name)) { open(Route.Movie(m)) } } }
                }
            }
        }
        if (series.isNotEmpty()) {
            item { Title("Séries") }
            item {
                LazyRow(state = rememberList("fav:se"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(series, key = { it.s.id }) { s -> Box(Modifier.width(110.dp)) { Poster(s.s.cover, Featured.shortTitle(s.s.name)) { open(Route.Series(s)) } } }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))

// ---- TV guide ---------------------------------------------------------------------------------------------------


/** A channel's programmes, day by day: the one on now plays live, a past one plays from the catch-up when the channel has it. */
@Composable
internal fun GuideChannelPage(core: AppCore, c: ChannelRow, player: VideoPlayer) {
    val settings by core.settings.flow.collectAsState(null)
    val now = remember { nowMillis() }
    val catchup by produceState(false, c.ch.id) { value = core.resolver.canCatchup(c.ch) }
    val programs by produceState<List<ProgramEntity>?>(null, c.ch.id, settings?.epgDays) {
        val back = c.ch.catchupDays.coerceIn(0, 7) * 86_400_000L
        value = core.epg.programs(c, now - back, now + (settings?.epgDays ?: 3) * 86_400_000L)
    }
    val scope = rememberCoroutineScope()
    val list = programs
    val firstLive = list?.indexOfFirst { it.stop > now } ?: -1
    val state = rememberLazyListState()
    LaunchedEffect(firstLive) { if (firstLive > 0) state.scrollToItem(firstLive + 1) }
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) { FavoriteStar(core, c) } }
        when {
            list == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            list.isEmpty() -> item { Text("Pas de programme pour cette chaîne dans le guide.", color = Palette.TextDim, modifier = Modifier.padding(16.dp)) }
            else -> list.forEachIndexed { i, pr ->
                if (i == 0 || dateOf(list[i - 1].start) != dateOf(pr.start)) item(key = "d" + pr.start) { Title(dayLabel(pr.start)) }
                item(key = "p" + pr.id) {
                    val live = pr.start <= now && pr.stop > now
                    val past = pr.stop <= now
                    val playable = live || (past && catchup)
                    Row(Modifier.fillMaxWidth().clickable(enabled = playable) {
                        scope.launch {
                            if (live) playChannel(core, player, c, pr.title)
                            else playCatchup(core, player, c, pr)
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Text(hm(pr.start), color = if (live) Palette.Accent else Palette.TextDim, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pr.title, fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, color = if (past && !catchup) Palette.TextDim else Palette.Text)
                            if (live) ProgressLine(((now - pr.start).toFloat() / (pr.stop - pr.start)).coerceIn(0f, 1f), Modifier.fillMaxWidth().padding(top = 6.dp))
                            pr.description?.takeIf { live }?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                        }
                        Text(when { live -> "● Direct"; past && catchup -> "↺ Revoir"; else -> "" }, color = if (live) Palette.Danger else Palette.Accent, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

// ---- Sport ------------------------------------------------------------------------------------------------------

/** The games of the chosen sports (official calendar) and the channels of the playlist that show them, as on the TV. */
@Composable
internal fun SportsPage(core: AppCore, p: PlaylistEntity, player: VideoPlayer) {
    val settings by core.settings.flow.collectAsState(null)
    val prefs = settings?.let { SportPrefs.of(it) }
    val funnel = SportsFunnel
    val sports = prefs?.sports.orEmpty()
    val days = funnel.days(prefs?.days ?: 7)
    // Read again every 30 s while a game is live (score, minute), every 5 min otherwise; the list stays meanwhile.
    var games by remember(p.id) { mutableStateOf<List<SportsRepository.Game>?>(null) }
    LaunchedEffect(p.id, prefs?.sports, prefs?.leagues, days) {
        val pr = prefs ?: return@LaunchedEffect
        games = null
        while (true) {
            games = if (pr.sports.isEmpty()) emptyList() else runCatching { core.sports.games(p.id, pr) }.getOrElse { games ?: emptyList() }
            kotlinx.coroutines.delay(if (FixtureClient.hasLive(games.orEmpty().map { it.fixture })) FixtureClient.LIVE_REFRESH_MS else 5 * 60_000L)
        }
    }
    val scope = rememberCoroutineScope()
    fun save(pr: SportPrefs) { scope.launch { core.settings.setSports(pr) } }
    val all = games.orEmpty()
    val clubs = remember(all) { funnel.clubs(all.map { it.fixture }) }
    val keep = remember(all, prefs?.teams) { funnel.byClubs(all.map { it.fixture }, prefs?.teams.orEmpty()).toSet() }
    val shown = games?.filter { it.fixture in keep }
    val leagues = funnel.leaguesOf(sports)
    var picker by remember { mutableStateOf<String?>(null) }
    var choose by remember { mutableStateOf<SportsRepository.Game?>(null) }
    fun watch(g: SportsRepository.Game) {
        val f = g.fixture
        when {
            g.channels.isNotEmpty() && !f.channelsOpen() -> Parental.notice = "${f.label}\n\nLa chaîne s'ouvre ${f.opensNote}."
            g.channels.isEmpty() -> Parental.notice = g.tv?.let { "Diffusé sur $it : cette chaîne n'est pas dans ta liste." }
                ?: listOfNotNull(f.usualBroadcaster, "aucune chaîne de ta liste ne l'annonce encore").joinToString(" · ").replaceFirstChar { it.uppercase() }
            g.channels.size == 1 -> scope.launch { playChannel(core, player, g.channels[0], f.label) }
            else -> choose = g
        }
    }

    LazyColumn(state = rememberList("sport", ready = games != null), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(funnel.sports, key = { it.id }) { sp ->
                    val on = sp.id in sports
                    PhoneChip("${sp.emoji}  ${sp.label}", selected = on) {
                        val cur = prefs ?: return@PhoneChip
                        save(cur.copy(sports = if (on) cur.sports - sp.id else cur.sports + sp.id))
                    }
                }
            }
        }
        // The rest as three menus, as on the Android phone.
        if (prefs != null && sports.isNotEmpty()) item {
            val comps = leagues.filter { it.id in prefs.leagues }.map { it.label }
            val picked = prefs.teams.filter { it in clubs }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (leagues.size > 1) item { MenuButton("Compétition", funnel.summary(comps, "Toutes", "compétitions"), comps.isNotEmpty()) { picker = "leagues" } }
                if (clubs.isNotEmpty() || picked.isNotEmpty()) item { MenuButton("Équipe", funnel.summary(picked, "Toutes", "équipes"), picked.isNotEmpty()) { picker = "clubs" } }
                item { MenuButton("Période", "$days jours", false) { picker = "days" } }
            }
        }
        when {
            prefs?.sports?.isEmpty() == true -> item {
                Empty("Choisis un sport", "Puis, si tu veux, une compétition et une équipe. Le choix est partagé avec la TV et le PC.")
            }
            shown == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            shown.isEmpty() -> item { Empty("Aucun match", "Pas de match en cours ni à venir sur $days jours avec ces filtres.") }
            else -> shown.groupBy { if (it.fixture.live) "🔴  En direct" else dayLabel(it.fixture.start) }.forEach { (day, gs) ->
                item(key = "h$day") {
                    Text(day, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
                }
                items(gs, key = { "g" + it.fixture.id }) { g -> GameCard(g) { watch(g) } }
            }
        }
    }

    choose?.let { g ->
        Options(g.fixture.label, g.fixture.league.label + " · choisis une chaîne", g.channels.map { c ->
            Option(c.ch.name) { scope.launch { playChannel(core, player, c, g.fixture.label) } }
        }) { choose = null }
    }
    val pr = prefs ?: return
    when (picker) {
        "leagues" -> PickDialog("Compétitions", "Aucune cochée : toutes celles de tes sports.",
            leagues.map { l -> l.id to ((if (sports.size > 1) SportsCatalog.byId(l.sport)?.emoji?.let { "$it  " } else null).orEmpty() + l.label) },
            pr.leagues, onToggle = { id -> save(pr.copy(leagues = if (id in pr.leagues) pr.leagues - id else pr.leagues + id)) },
            onClear = { save(pr.copy(leagues = pr.leagues - leagues.map { it.id }.toSet())) }) { picker = null }
        "clubs" -> PickDialog("Équipes", "Aucune cochée : tous les matchs.", (clubs + pr.teams.filter { it !in clubs }).map { it to it },
            pr.teams.toSet(), onToggle = { c -> save(pr.copy(teams = if (c in pr.teams) pr.teams - c else pr.teams + c)) },
            onClear = { save(pr.copy(teams = emptyList())) }) { picker = null }
        "days" -> Options("Période", null, funnel.DAY_CHOICES.map { n -> Option("$n jours", n == days) { if (n != days) save(pr.copy(days = n)) } }) { picker = null }
    }
}

@Composable
private fun Empty(title: String, text: String) = Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
    Text(text, color = Palette.TextDim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
}

/** A filter as one button: "Compétition · Toutes ▾"; it opens its list. */
@Composable
private fun MenuButton(title: String, value: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.height(38.dp).clip(shape).background(if (active) Palette.AccentSoft else Color(0x1FFFFFFF))
        .border(1.dp, if (active) Palette.Accent else Color(0x1FFFFFFF), shape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("$title · ", fontSize = 14.sp, color = Palette.TextDim, maxLines = 1)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 170.dp))
        Text("  ▾", fontSize = 14.sp, color = Palette.Accent)
    }
}

/** A list to tick several entries in; it stays open until "Terminé". */
@Composable
private fun PickDialog(title: String, subtitle: String, items: List<Pair<String, String>>, selected: Set<String>,
                       onToggle: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val none = items.none { it.first in selected }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.Panel,
        title = { Text(title) },
        text = {
            Column {
                Text(subtitle, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item(key = "\u0000all") { PickRow(if (title == "Équipes") "Tous les matchs" else "Toutes", none, onClear) }
                    items(items, key = { it.first }) { (id, label) -> PickRow(label, id in selected) { onToggle(id) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Terminé") } },
    )
}

@Composable
private fun PickRow(label: String, on: Boolean, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Text, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Text(if (on) "✓" else "", color = Palette.Accent, fontWeight = FontWeight.Bold)
    }

/**
 * A game: competition and stadium, the time (or EN DIRECT and the minute) with its channel, then the two teams face
 * to face with their crests. The whole card plays (or offers the channels).
 */
@Composable
private fun GameCard(g: SportsRepository.Game, onClick: () -> Unit) {
    val f = g.fixture
    val now by produceState(nowMillis()) { while (true) { kotlinx.coroutines.delay(60_000); value = nowMillis() } }
    val open = g.channels.isNotEmpty() && f.channelsOpen(now)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(listOfNotNull(f.details, f.venue).joinToString(" · "), color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (f.live) {
                Text("EN DIRECT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Palette.Danger).padding(horizontal = 6.dp, vertical = 2.dp))
                Text("  " + f.clock, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Danger, maxLines = 1)
            } else Text(hm(f.start), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.width(12.dp))
            val ch = when {
                g.channels.isNotEmpty() -> (if (open) "▶  " else "") + g.channels.first().ch.name
                g.tv != null -> "Sur ${g.tv}"
                else -> f.usualBroadcaster ?: ""
            }
            val after = when {
                g.channels.isNotEmpty() && !open -> " · dès " + hm(f.start - Fixture.OPEN_BEFORE_MS)
                g.channels.size > 1 -> "  +${g.channels.size - 1}"
                else -> null
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (ch.isNotEmpty()) Text(ch, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (open) Palette.Text else Palette.TextDim, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                        .then(if (open) Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.AccentSoft).padding(horizontal = 10.dp, vertical = 5.dp) else Modifier))
                after?.let { Text(it, fontSize = 13.sp, color = Palette.TextDim, maxLines = 1) }
            }
        }
        val home = f.home
        val away = f.away
        if (home != null && away != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamSide(home.short ?: home.name, home.logo, end = true, modifier = Modifier.weight(1f))
            Text(if (f.live && f.homeScore != null) "${f.homeScore} – ${f.awayScore}" else "vs", fontSize = if (f.live) 20.sp else 14.sp,
                fontWeight = FontWeight.Bold, color = if (f.live) Palette.Text else Palette.TextDim, modifier = Modifier.padding(horizontal = 12.dp))
            TeamSide(away.short ?: away.name, away.logo, end = false, modifier = Modifier.weight(1f))
        } else Text(f.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp))
    }
}

/** A team: its crest next to the "vs", its name outside (on two lines rather than cut). */
@Composable
private fun TeamSide(name: String, logo: String?, end: Boolean, modifier: Modifier) =
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        val text = @Composable {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp))
        }
        if (end) text()
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (logo != null) AsyncImage(logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text(name.take(1), fontWeight = FontWeight.Bold, color = Palette.TextDim)
        }
        if (!end) text()
    }

// ---- Downloads --------------------------------------------------------------------------------------------------

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> com.lumentv.core.oneDecimal(bytes / 1e9) + " Go"
    else -> (bytes / 1_000_000).toString() + " Mo"
}

/** Films and episodes kept on the iPhone: progress, play, cancel, try again, delete. */
@Composable
internal fun DownloadsPage(core: AppCore, player: VideoPlayer) {
    val downloads = LocalDownloads.current ?: return
    val list by remember { downloads.observe() }.collectAsState(null)
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<com.lumentv.data.db.TransferEntity?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        val l = list
        if (l != null && l.isEmpty()) item {
            Text("Aucun téléchargement. Sur la page d'un film, « ⬇ Télécharger » ; pour un épisode, son menu ⋮. Les fichiers se lisent ensuite sans réseau.",
                color = Palette.TextDim, modifier = Modifier.padding(16.dp))
        }
        items(l.orEmpty(), key = { it.id }) { t ->
            val done = t.status == com.lumentv.data.db.TransferStatus.DONE
            Row(Modifier.fillMaxWidth().clickable {
                if (done) scope.launch {
                    val key = com.lumentv.data.repo.LibraryRepository.key(if (t.refKind == "mv") com.lumentv.data.db.HistoryKind.MOVIE else com.lumentv.data.db.HistoryKind.EPISODE, t.playlistId, t.refUid)
                    val h = core.library.history(key)
                    val from = h?.takeIf { it.durationMs > 0 && it.positionMs < it.durationMs * 0.95 }?.positionMs ?: 0
                    play(core, player, com.lumentv.data.repo.StreamSpec(DownloadManager.SCHEME + t.filePath, null, null, false), Featured.shortTitle(t.title), t.subtitle, from) { pos, dur ->
                        core.scope.launch {
                            core.library.record(com.lumentv.data.db.HistoryEntity(key, if (t.refKind == "mv") com.lumentv.data.db.HistoryKind.MOVIE else com.lumentv.data.db.HistoryKind.EPISODE,
                                t.playlistId, t.refUid, t.title, t.subtitle, null, t.seriesUid, nowMillis(), if (dur > 0 && pos > dur * 0.95) dur else pos, dur))
                        }
                    }
                } else menu = t
            }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Featured.shortTitle(t.title), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    t.subtitle?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    val line = when (t.status) {
                        com.lumentv.data.db.TransferStatus.DONE -> "Téléchargé · " + size(t.bytes)
                        com.lumentv.data.db.TransferStatus.SCHEDULED -> "En attente"
                        com.lumentv.data.db.TransferStatus.RUNNING ->
                            if (t.totalBytes > 0) "${t.bytes * 100 / t.totalBytes} % · ${size(t.bytes)} sur ${size(t.totalBytes)}" else "En cours…"
                        com.lumentv.data.db.TransferStatus.CANCELLED -> "Annulé"
                        else -> "Échec" + (t.error?.let { " · $it" } ?: "")
                    }
                    Text(line, color = if (t.status == com.lumentv.data.db.TransferStatus.FAILED) Palette.Danger else Palette.TextDim, fontSize = 12.sp, maxLines = 2)
                    if (t.status == com.lumentv.data.db.TransferStatus.RUNNING && t.totalBytes > 0)
                        ProgressLine(t.bytes.toFloat() / t.totalBytes, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                Text("⋮", fontSize = 22.sp, color = Palette.TextDim, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { menu = t }.padding(10.dp))
            }
        }
    }
    menu?.let { t ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { menu = null }, containerColor = Palette.Panel,
            title = { Text(Featured.shortTitle(t.title), maxLines = 2) },
            text = {
                Column {
                    val running = t.status == com.lumentv.data.db.TransferStatus.RUNNING || t.status == com.lumentv.data.db.TransferStatus.SCHEDULED
                    val retry = t.status == com.lumentv.data.db.TransferStatus.FAILED || t.status == com.lumentv.data.db.TransferStatus.CANCELLED
                    if (running) MenuLine("Annuler") { scope.launch { downloads.cancel(t.id) }; menu = null }
                    if (retry) MenuLine("Réessayer") { scope.launch { downloads.retry(t.id) }; menu = null }
                    MenuLine("Supprimer") { scope.launch { downloads.delete(t.id) }; menu = null }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { menu = null }) { Text("Fermer") } })
    }
}

@Composable
private fun MenuLine(text: String, onClick: () -> Unit) =
    Text(text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp))
