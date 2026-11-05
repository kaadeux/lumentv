package com.lumentv.phone

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

private const val FAVORITES = "★ Mes chaînes"

/** The channels of a group (or Ma liste) with what is on; a channel opens its programmes. */
@Composable
internal fun GuidePage(core: AppCore, p: PlaylistEntity, open: (Route) -> Unit) {
    // Locked groups stay out of the guide until their PIN is given (in the TV tab).
    val groups by produceState(emptyList<String>(), p.id) {
        value = listOf(FAVORITES) + core.library.groups(p.id, false).filter { !it.locked || Parental.isUnlocked(p.id, it.name) }.map { it.name }
    }
    val memory = LocalScroll.current
    var group by remember(p.id) { mutableStateOf(memory.values["guide"] as? String ?: FAVORITES) }
    val settings by core.settings.flow.collectAsState(null)
    val channels by produceState<List<ChannelRow>?>(null, p.id, group) {
        value = if (group == FAVORITES) core.library.favoriteChannelsAll().filter { it.ch.playlistId == p.id }
        else {
            val locked = core.library.lockedGroups(p.id).filterTo(HashSet()) { !Parental.isUnlocked(p.id, it) }
            core.library.channels(p.id, group, false, settings?.sort ?: "provider").filter { it.ch.groupName !in locked }
        }
    }
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups) { g -> FilterChip(selected = g == group, onClick = { group = g; memory.values["guide"] = g; memory.forget("guide:list") }, label = { Text(g, maxLines = 1) }) }
        }
        LazyColumn(state = rememberList("guide:list:$group", ready = channels != null), contentPadding = PaddingValues(vertical = 8.dp)) {
            val list = channels
            if (list == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (list.isEmpty()) item {
                Text(if (group == FAVORITES) "Aucune chaîne dans Ma liste : ajoute-en avec ☆ dans l'onglet TV." else "Aucune chaîne.",
                    color = Palette.TextDim, modifier = Modifier.padding(16.dp))
            }
            else items(list, key = { it.ch.id }) { c -> ChannelLine(core, c, { open(Route.GuideChannel(c)) }) { Text("›", fontSize = 22.sp, color = Palette.TextDim, modifier = Modifier.padding(8.dp)) } }
        }
    }
}

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
                            else core.resolver.catchup(c.ch, pr)?.let { spec ->
                                play(core, player, spec, pr.title, c.ch.name + " · " + dayLabel(pr.start) + " " + hm(pr.start), 0, c.ch.playlistId) { _, _ -> }
                            }
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
    val s = settings
    val prefs = s?.let { SportPrefs.of(it) }
    var games by remember(p.id) { mutableStateOf<List<SportsRepository.Game>?>(null) }
    LaunchedEffect(p.id, prefs) {
        if (prefs == null) return@LaunchedEffect
        games = null
        games = runCatching { core.sports.games(p.id, prefs) }.getOrDefault(emptyList())
    }
    val scope = rememberCoroutineScope()
    LazyColumn(state = rememberList("sport", ready = games != null), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SportsFunnel.sports, key = { it.id }) { sp ->
                    val on = prefs?.sports?.contains(sp.id) == true
                    FilterChip(selected = on, label = { Text("${sp.emoji} ${sp.label}") }, onClick = {
                        val cur = prefs ?: return@FilterChip
                        scope.launch { core.settings.setSports(cur.copy(sports = if (on) cur.sports - sp.id else cur.sports + sp.id)) }
                    })
                }
            }
        }
        // Competitions of the chosen sports (none ticked: all), clubs of the listed games (none: all), the period.
        if (prefs != null && prefs.sports.isNotEmpty()) {
            val leagues = SportsFunnel.leaguesOf(prefs.sports)
            if (leagues.size > 1) item {
                FilterRow("Compétitions", leagues.map { it.id to it.label }, prefs.leagues,
                    onClear = { scope.launch { core.settings.setSports(prefs.copy(leagues = emptySet())) } }) { id, on ->
                    scope.launch { core.settings.setSports(prefs.copy(leagues = if (on) prefs.leagues + id else prefs.leagues - id)) }
                }
            }
            val clubs = games?.let { g -> SportsFunnel.clubs(g.map { it.fixture }) }.orEmpty()
            if (clubs.size > 1) item {
                FilterRow("Équipes", clubs.map { it to it }, prefs.teams.toSet(),
                    onClear = { scope.launch { core.settings.setSports(prefs.copy(teams = emptyList())) } }) { name, on ->
                    scope.launch { core.settings.setSports(prefs.copy(teams = if (on) prefs.teams + name else prefs.teams - name)) }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Période", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, end = 4.dp)) }
                    items(SportsFunnel.DAY_CHOICES) { d ->
                        FilterChip(selected = SportsFunnel.days(prefs.days) == d, label = { Text("$d jours") }, onClick = {
                            scope.launch { core.settings.setSports(prefs.copy(days = d)) }
                        })
                    }
                }
            }
        }
        val list = games?.let { g ->
            val keep = SportsFunnel.byClubs(g.map { it.fixture }, prefs?.teams.orEmpty()).toSet()
            g.filter { it.fixture in keep }
        }
        when {
            prefs?.sports?.isEmpty() == true -> item { Text("Choisis un ou plusieurs sports ci-dessus.", color = Palette.TextDim, modifier = Modifier.padding(16.dp)) }
            list == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            list.isEmpty() -> item { Text("Aucun match à venir pour ces sports.", color = Palette.TextDim, modifier = Modifier.padding(16.dp)) }
            else -> list.forEachIndexed { i, g ->
                val f = g.fixture
                if (i == 0 || (!f.live && (list[i - 1].fixture.live || dateOf(list[i - 1].fixture.start) != dateOf(f.start))))
                    item(key = "d" + f.id) { Title(if (f.live) "En direct" else dayLabel(f.start)) }
                item(key = "g" + f.id) { GameCard(g) { c -> scope.launch { playChannel(core, player, c, f.label) } } }
            }
        }
    }
}

/** A row of chips to tick ([chosen] empty: "Toutes", nothing filtered). */
@Composable
private fun FilterRow(title: String, options: List<Pair<String, String>>, chosen: Set<String>, onClear: () -> Unit, onToggle: (String, Boolean) -> Unit) =
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(title, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, end = 4.dp)) }
        item { FilterChip(selected = chosen.isEmpty(), label = { Text(if (title == "Équipes") "Tous" else "Toutes") }, onClick = onClear) }
        items(options, key = { it.first }) { (id, label) ->
            val on = id in chosen
            FilterChip(selected = on, label = { Text(label, maxLines = 1) }, onClick = { onToggle(id, !on) })
        }
    }

@Composable
private fun GameCard(g: SportsRepository.Game, onChannel: (ChannelRow) -> Unit) {
    val f = g.fixture
    val open = f.channelsOpen()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(14.dp)) {
        Text(f.details, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (f.live) f.clock else hm(f.start), color = if (f.live) Palette.Danger else Palette.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.width(64.dp))
            if (f.home != null && f.away != null) {
                TeamSide(f.home!!.short ?: f.home!!.name, f.home!!.logo, f.homeScore, Modifier.weight(1f))
                Text("–", color = Palette.TextDim, modifier = Modifier.padding(horizontal = 6.dp))
                TeamSide(f.away!!.short ?: f.away!!.name, f.away!!.logo, f.awayScore, Modifier.weight(1f))
            } else Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        when {
            g.channels.isNotEmpty() && open -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(g.channels, key = { it.ch.id }) { c ->
                    Text("▶ " + c.ch.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.AccentSoft).clickable { onChannel(c) }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            g.channels.isNotEmpty() -> Text((g.tv ?: g.channels.first().ch.name) + " · " + f.opensNote, color = Palette.TextDim, fontSize = 12.sp)
            g.tv != null -> Text("Sur ${g.tv}", color = Palette.TextDim, fontSize = 12.sp)
            else -> f.usualBroadcaster?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun TeamSide(name: String, logo: String?, score: String?, modifier: Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    if (logo != null) AsyncImage(logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(26.dp))
    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 6.dp))
    score?.let { Text(it, fontWeight = FontWeight.Bold, color = Palette.Accent, modifier = Modifier.padding(start = 4.dp)) }
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
