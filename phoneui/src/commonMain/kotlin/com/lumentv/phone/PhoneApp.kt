package com.lumentv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import coil3.compose.AsyncImage
import com.lumentv.AppCore
import com.lumentv.core.Featured
import com.lumentv.core.SecretBox
import com.lumentv.core.nowMillis
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.repo.Groups
import com.lumentv.data.repo.StreamSpec
import com.lumentv.data.settings.Keys
import com.lumentv.data.xtream.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the viewer is, above the bottom tabs. */
internal sealed interface Route {
    data class Movie(val row: MovieRow) : Route
    data class Series(val row: SeriesRow) : Route
    data class Categories(val series: Boolean) : Route
    data object Favorites : Route
    data object Guide : Route
    data class GuideChannel(val channel: ChannelRow) : Route
    data object Sports : Route
    data object Settings : Route
    data object Downloads : Route
    data class PlaylistEdit(val id: Long, val type: String) : Route
}

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    MOVIES("Films", androidx.compose.material.icons.Icons.Filled.PlayArrow),
    SERIES("Séries", androidx.compose.material.icons.Icons.AutoMirrored.Filled.List),
    LIVE("TV", androidx.compose.material.icons.Icons.Filled.Home),
    SEARCH("Recherche", androidx.compose.material.icons.Icons.Filled.Search),
    MORE("Plus", androidx.compose.material.icons.Icons.Filled.Menu),
}

/** The title bar's words for a page (as the Android phone's: "Film", "Série", "Paramètres"…). */
private fun titleOf(r: Route): String = when (r) {
    is Route.Movie -> "Film"
    is Route.Series -> "Série"
    is Route.Categories -> if (r.series) "Séries" else "Films"
    Route.Favorites -> "Favoris"
    Route.Guide -> "Guide TV"
    is Route.GuideChannel -> r.channel.ch.name
    Route.Sports -> "Sport"
    Route.Settings -> "Paramètres"
    Route.Downloads -> "Téléchargés"
    is Route.PlaylistEdit -> if (r.id != 0L) "Modifier la liste" else if (r.type == PlaylistType.XTREAM) "Compte Xtream" else "Playlist M3U"
}

/**
 * The iPhone app (and, step by step, the Android phone's): the account to add first, then Films, Séries and TV over
 * the shared database. Playback goes to the platform's [player].
 */
@Composable
fun LumenPhoneApp(core: AppCore, player: VideoPlayer, access: AccessGate? = null) = LumenTheme {
    val playlists by core.playlists.observe().collectAsState(null)
    val settings by core.settings.flow.collectAsState(null)
    val list = playlists
    val s = settings
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when {
            access != null && !access.unlocked -> AccessScreen(access)
            list == null || s == null -> Centered { CircularProgressIndicator() }
            list.isEmpty() -> SetupScreen(core)
            else -> {
                val active = list.firstOrNull { it.id == s.activePlaylistId } ?: list.first()
                if (active.lastRefresh == 0L) FirstLoad(core, active, others = list.size > 1)
                // Another list: its own pages, from the top.
                else androidx.compose.runtime.key(active.id) { MainScreen(core, active, player) }
            }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }

// ---- First start: the first list ----------------------------------------------------------------------------------

@Composable
private fun SetupScreen(core: AppCore) = Column(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
    Spacer(Modifier.height(24.dp))
    Text("lumenTV", fontSize = 34.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text("Ajoute ta première liste : une playlist M3U ou un compte Xtream.", color = Palette.TextDim)
    Spacer(Modifier.height(16.dp))
    PlaylistForm(core, null, PlaylistType.XTREAM, Modifier.weight(1f), onSaved = {})
}

/** The list is read once after the account is added (a minute or two for 30 000 titles). */
@Composable
private fun FirstLoad(core: AppCore, p: PlaylistEntity, others: Boolean) {
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var attempt by remember(p.id) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(p.id, attempt) {
        error = null
        val r = core.playlists.refresh(p.id)
        // The guide next, in the app's scope: this screen goes away as soon as the list is in.
        if (r.ok) core.scope.launch { core.epg.refresh(p.id) } else error = r.message
    }
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error == null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Chargement de ta liste…", fontWeight = FontWeight.SemiBold)
                Text("Une à deux minutes la première fois.", color = Palette.TextDim)
            } else {
                Text(error!!, color = Palette.Danger)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { attempt++ }) { Text("Réessayer") }
                OutlinedButton(onClick = { scope.launch { core.playlists.delete(p.id) } }) { Text(if (others) "Retirer cette liste" else "Changer de liste") }
            }
        }
    }
}

// ---- Main screen: tabs and pages --------------------------------------------------------------------------------

@Composable
private fun MainScreen(core: AppCore, p: PlaylistEntity, player: VideoPlayer) {
    var tab by remember { mutableStateOf(Tab.MOVIES) }
    val stack = remember { mutableStateListOf<Route>() }
    val open: (Route) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
    // Where each list was: kept while going into a title and back, emptied when another tab is opened (it starts at the top).
    val memory = remember { ScrollMemory() }
    androidx.compose.runtime.CompositionLocalProvider(LocalScroll provides memory) {
    // The Android phone's frame: its title bar (lumenTV and the tab, or ← and the page) and its tabs at the bottom.
    Column(Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
        androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
        val route = stack.lastOrNull()
        TitleBar(if (route == null) null else titleOf(route), tab.label, onBack = back, onSearch = if (tab != Tab.SEARCH || route != null) ({
            tab = Tab.SEARCH; stack.clear(); memory.clear()
        }) else null)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val r = stack.lastOrNull()) {
                is Route.Movie -> MoviePage(core, r.row, player)
                is Route.Series -> SeriesPage(core, p, r.row, player)
                Route.Favorites -> FavoritesPage(core, p, player, open)
                Route.Guide -> GuidePage(core, p, player, open)
                is Route.GuideChannel -> GuideChannelPage(core, r.channel, player)
                Route.Sports -> SportsPage(core, p, player)
                Route.Settings -> SettingsPage(core, p, open)
                is Route.PlaylistEdit -> PlaylistEditPage(core, r.id, r.type, back)
                Route.Downloads -> DownloadsPage(core, player)
                is Route.Categories -> if (r.series) SeriesCategories(core, p, open) else MovieCategories(core, p, open)
                null -> when (tab) {
                    Tab.MOVIES -> MoviesHome(core, p, player, open)
                    Tab.SERIES -> SeriesHome(core, p, player, open)
                    Tab.LIVE -> LiveHome(core, p, player, open)
                    Tab.SEARCH -> SearchHome(core, p, player, open)
                    Tab.MORE -> MoreHome(open)
                }
            }
        }
        Tabs(tab) { t -> tab = t; stack.clear(); memory.clear() }
    }
    PinHost(core)
    // Another device of the subscription is playing: the TV's warning, then play anyway or not.
    val pending by Playback.pending.collectAsState()
    pending?.let { w ->
        com.lumentv.ui.kit.KitConfirm("Un autre écran regarde", w.text, "Lancer quand même", onConfirm = { w.go() },
            onDismiss = { Playback.pending.value = null })
    }
    }
}

/** "lumenTV• Films" on a tab, "← Film" on a page; the magnifier opens the search. */
@Composable
private fun TitleBar(page: String?, tab: String, onBack: () -> Unit, onSearch: (() -> Unit)?) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically,
) {
    if (page != null) IconTap(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Retour", onBack)
    else Spacer(Modifier.width(12.dp))
    if (page == null) Row(Modifier.weight(1f).padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text("lumen", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Text("TV", fontSize = 22.sp, fontWeight = FontWeight.Light, color = Palette.Text)
        Box(Modifier.padding(start = 2.dp, bottom = 6.dp).size(5.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Accent))
        Text("   $tab", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.TextDim, maxLines = 1, modifier = Modifier.padding(bottom = 1.dp))
    } else Text(page, fontSize = 22.sp, fontWeight = FontWeight.Normal, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
    if (onSearch != null) IconTap(androidx.compose.material.icons.Icons.Filled.Search, "Rechercher", onSearch)
}

@Composable
private fun IconTap(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, contentDescription = label, tint = Palette.Text, modifier = Modifier.size(24.dp))
    }

/** The tabs at the bottom: icon, word, and an orange dash under the one open (the Android phone's). */
@Composable
private fun Tabs(current: Tab, onPick: (Tab) -> Unit) = Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xF20E0E10))) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(androidx.compose.ui.graphics.Color(0x14FFFFFF)))
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
        androidx.compose.foundation.layout.WindowInsetsSides.Bottom + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
        Tab.entries.forEach { t ->
            val on = t == current
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onPick(t) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Icon(t.icon, contentDescription = null, tint = if (on) Palette.Text else Palette.TextDim,
                    modifier = Modifier.padding(vertical = 2.dp).size(24.dp))
                Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (on) Palette.Text else Palette.TextDim)
                Box(Modifier.padding(top = 3.dp).size(if (on) 16.dp else 0.dp, 3.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Accent))
            }
        }
    }
}

@Composable
private fun Chips(names: List<String>, selected: String?, key: String = "chips", hidden: Set<String> = emptySet(), onMore: (() -> Unit)? = null,
                  onPick: (String) -> Unit) = LazyRow(
    state = rememberList(key),
    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    if (onMore != null) item { FilterChip(selected = false, onClick = onMore, label = { Text("⋮") }) }
    items(names) { n -> FilterChip(selected = n == selected, onClick = { onPick(n) }, label = { Text(n + if (n in hidden) " · masquée" else "", maxLines = 1) }) }
}

/** The ⋮ of a film or series category: hide it (it leaves the lists and the homes) or show it again. */
@Composable
private fun CategoryMenu(core: AppCore, p: PlaylistEntity, kind: String, cat: String, hidden: Boolean, onHidden: () -> Unit, onDismiss: () -> Unit) =
    Options(cat, null, listOf(Option(if (hidden) "Afficher la catégorie" else "Masquer la catégorie") {
        core.scope.launch { core.library.setHidden(p.id, kind, cat, !hidden); if (!hidden) onHidden() }
    }), onDismiss)

@Composable
internal fun Poster(url: String?, title: String, progress: Float? = null, onClick: () -> Unit) = Column(Modifier.clickable(onClick = onClick)) {
    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Palette.PanelHigh)) {
        Text(title, color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp), maxLines = 3)
        AsyncImage(url, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)) }
    }
    Text(title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
}

private const val RECENT = "Ajoutés récemment"

/**
 * As on the TV, the phone and the PC (Featured): one poster per title, the copies of a film ("4K MULTI", "FHD VF",
 * "VOSTFR"…) behind it, and the Arabic copies and categories left out.
 */
internal fun <T> oneEach(list: List<T>, name: (T) -> String, category: (T) -> String): List<T> =
    list.filter { !Featured.foreign(name(it), category(it)) }.distinctBy { Featured.baseTitle(name(it)) }

/** "Reprendre la lecture": films and episodes started and not finished. */
@Composable
internal fun ResumeRow(core: AppCore, p: PlaylistEntity, kind: String, onOpen: (HistoryEntity) -> Unit) {
    val history by core.library.observeHistory(200).collectAsState(emptyList())
    val items = history.filter { it.playlistId == p.id && it.kind == kind && it.durationMs > 0 && it.positionMs < it.durationMs * 0.95 && it.positionMs > 60_000 }
        .distinctBy { it.parentUid ?: Featured.baseTitle(it.title) }.take(20)
    if (items.isEmpty()) return
    Text("Reprendre la lecture", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
    LazyRow(state = rememberList("resume:$kind"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items) { h ->
            Box(Modifier.width(110.dp)) {
                Poster(h.image, Featured.shortTitle(h.title) + (h.subtitle?.substringBefore(" · ")?.let { " · $it" } ?: ""), h.positionMs.toFloat() / h.durationMs) { onOpen(h) }
            }
        }
    }
}

@Composable
private fun MovieCategories(core: AppCore, p: PlaylistEntity, open: (Route) -> Unit) {
    val settings by core.settings.flow.collectAsState(null)
    val showHidden = settings?.showHidden ?: false
    val changes by core.library.changes.collectAsState()
    val cats by produceState(emptyList<String>(), p.id, p.lastRefresh, showHidden, changes) {
        value = listOf(RECENT) + core.library.movieCategories(p.id, showHidden).map { it.category }.filter { !Featured.foreign("", it) }
    }
    val hidden by produceState(emptySet<String>(), p.id, changes) { value = core.library.hiddenOf(p.id, com.lumentv.data.db.PrefKind.MOVIE_CATEGORY) }
    var catMenu by remember { mutableStateOf(false) }
    val memory = LocalScroll.current
    var cat by remember(p.id) { mutableStateOf(memory.values["cat:mv"] as? String ?: RECENT) }
    val movies by produceState<List<MovieRow>?>(null, p.id, cat, p.lastRefresh) {
        val rows = if (cat == RECENT) core.library.recentMovies(p.id) else core.library.movies(p.id, cat)
        // Thousands of titles: sorted out off the screen's thread.
        value = withContext(Dispatchers.Default) { oneEach(rows, { it.m.name }, { it.m.category }) }
    }
    Column {
        Chips(cats, cat, "chips:mv", hidden, onMore = if (cat != RECENT) ({ catMenu = true }) else null) { cat = it; memory.values["cat:mv"] = it; memory.forget("grid:mv") }
        if (catMenu) CategoryMenu(core, p, com.lumentv.data.db.PrefKind.MOVIE_CATEGORY, cat, cat in hidden, onHidden = { if (!showHidden) cat = RECENT }) { catMenu = false }
        LazyVerticalGrid(GridCells.Adaptive(108.dp), state = rememberGrid("grid:mv:$cat", ready = movies != null), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(movies.orEmpty(), key = { it.m.id }) { m -> Poster(m.m.poster, Featured.shortTitle(m.m.name)) { open(Route.Movie(m)) } }
        }
    }
}

@Composable
private fun SeriesCategories(core: AppCore, p: PlaylistEntity, open: (Route) -> Unit) {
    val settings by core.settings.flow.collectAsState(null)
    val showHidden = settings?.showHidden ?: false
    val changes by core.library.changes.collectAsState()
    val cats by produceState(emptyList<String>(), p.id, p.lastRefresh, showHidden, changes) {
        value = listOf(RECENT) + core.library.seriesCategories(p.id, showHidden).map { it.category }.filter { !Featured.foreign("", it) }
    }
    val hidden by produceState(emptySet<String>(), p.id, changes) { value = core.library.hiddenOf(p.id, com.lumentv.data.db.PrefKind.SERIES_CATEGORY) }
    var catMenu by remember { mutableStateOf(false) }
    val memory = LocalScroll.current
    var cat by remember(p.id) { mutableStateOf(memory.values["cat:se"] as? String ?: RECENT) }
    val series by produceState<List<SeriesRow>?>(null, p.id, cat, p.lastRefresh) {
        val rows = if (cat == RECENT) core.library.recentSeries(p.id) else core.library.series(p.id, cat)
        value = withContext(Dispatchers.Default) { oneEach(rows, { it.s.name }, { it.s.category }) }
    }
    Column {
        Chips(cats, cat, "chips:se", hidden, onMore = if (cat != RECENT) ({ catMenu = true }) else null) { cat = it; memory.values["cat:se"] = it; memory.forget("grid:se") }
        if (catMenu) CategoryMenu(core, p, com.lumentv.data.db.PrefKind.SERIES_CATEGORY, cat, cat in hidden, onHidden = { if (!showHidden) cat = RECENT }) { catMenu = false }
        LazyVerticalGrid(GridCells.Adaptive(108.dp), state = rememberGrid("grid:se:$cat", ready = series != null), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(series.orEmpty(), key = { it.s.id }) { s -> Poster(s.s.cover, Featured.shortTitle(s.s.name)) { open(Route.Series(s)) } }
        }
    }
}

// ---- Pages ------------------------------------------------------------------------------------------------------

internal fun minutes(ms: Long) = (ms / 60_000).let { if (it >= 60) "${it / 60} h ${(it % 60).toString().padStart(2, '0')}" else "$it min" }

/** Plays through [Playback] (languages, the subscription's other screens); [playlistId] null for a downloaded file. */
internal suspend fun play(core: AppCore, player: VideoPlayer, spec: StreamSpec, title: String, subtitle: String?, startMs: Long,
                          playlistId: Long? = null, variants: List<PlayVariant> = emptyList(), onFinish: (Long, Long) -> Unit) {
    // Over HTTPS when the provider's server answers there: the internet provider no longer reads what is played.
    val url = com.lumentv.core.HttpsUpgrade.stream(spec.url)
    val request = PlayRequest(url, spec.userAgent, spec.referer, title, subtitle, startMs, spec.live, onFinish)
    if (url != spec.url) request.fallbackUrl = spec.url
    request.variants = variants
    Playback.start(core, player, request, playlistId)
}

// ---- Live -------------------------------------------------------------------------------------------------------

/**
 * The Android phone's Direct: the groups as pills ("Groupes ▾" lists them all), then the channels of the one chosen,
 * with what is on, a filter and the order; a channel plays, its ⋮ adds it to Mes chaînes or opens its programmes.
 */
@Composable
private fun LiveHome(core: AppCore, p: PlaylistEntity, player: VideoPlayer, open: (Route) -> Unit) {
    val memory = LocalScroll.current
    val settings by core.settings.flow.collectAsState(null)
    val sort = settings?.sort ?: "provider"
    val showHidden = settings?.showHidden ?: false
    val changes by core.library.changes.collectAsState()
    val groups by produceState(emptyList<com.lumentv.data.repo.GroupInfo>(), p.id, p.lastRefresh, changes, showHidden) { value = core.library.groups(p.id, showHidden) }
    var group by remember(p.id) { mutableStateOf(memory.values["live:group"] as? String ?: Groups.ALL) }
    var filter by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<ChannelRow?>(null) }
    var groupMenu by remember { mutableStateOf(false) }
    val info = groups.firstOrNull { it.name == group }
    // A locked group shows nothing until the PIN is given.
    val locked = info?.locked == true && !Parental.isUnlocked(p.id, group)
    val channels by produceState<List<ChannelRow>?>(null, p.id, group, sort, changes, showHidden, locked) {
        value = if (locked) emptyList() else core.library.channels(p.id, group, showHidden, sort)
    }
    val shown = remember(channels, filter) {
        val f = com.lumentv.core.normalize(filter)
        channels?.let { all -> if (f.isEmpty()) all else all.filter { it.ch.nameNorm.contains(f) || it.ch.number.toString().startsWith(f) } }
    }
    val scope = rememberCoroutineScope()
    fun pick(g: String) {
        group = g; memory.values["live:group"] = g; filter = ""
        if (groups.firstOrNull { it.name == g }?.locked == true) scope.launch { Parental.unlockGroup(core, p.id, g) }
    }
    val pills = listOf(Groups.FAVORITES to "★ Mes chaînes", Groups.ALL to "Toutes") +
        groups.map { it.name to it.name + (if (it.locked) "  🔒" else "") + (if (it.hidden) " · masqué" else "") }
    Column(Modifier.fillMaxSize()) {
        LazyRow(state = rememberList("live:pills"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "picker") { PhoneChip("Groupes  ▾") { picker = true } }
            items(pills, key = { it.first }) { (name, label) -> PhoneChip(label, selected = name == group) { pick(name) } }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (group) { Groups.ALL -> "Chaînes"; Groups.FAVORITES -> "Favoris"; else -> group }, fontSize = 22.sp, color = Palette.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (info != null) Box(Modifier.padding(end = 8.dp)) { PhoneChip("⋮") { groupMenu = true } }
            if (group != Groups.FAVORITES) PhoneChip(when (sort) { "az" -> "A → Z"; "za" -> "Z → A"; else -> "Ordre" }) {
                core.scope.launch { core.settings.set(Keys.sort, when (sort) { "provider" -> "az"; "az" -> "za"; else -> "provider" }) }
            }
        }
        OutlinedTextField(filter, { filter = it }, placeholder = { Text("Rechercher dans cette liste", color = Palette.TextDim) }, singleLine = true,
            leadingIcon = { Text("🔍") }, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp))
        val list = shown
        when {
            list == null -> Centered { CircularProgressIndicator() }
            locked -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔒  Groupe verrouillé", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Saisis le code PIN pour afficher ces chaînes.", color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                PhoneButton("Saisir le code PIN", primary = true, modifier = Modifier.fillMaxWidth(0.7f)) { scope.launch { Parental.unlockGroup(core, p.id, group) } }
            }
            list.isEmpty() -> Text(when {
                filter.isNotBlank() -> "Rien ne correspond à « $filter » dans cette liste."
                group == Groups.FAVORITES -> "Pas encore de favoris : touchez ⋮ à droite d'une chaîne, puis « Ajouter aux favoris »."
                else -> "Ce groupe est vide ou toutes ses chaînes sont masquées."
            }, color = Palette.TextDim, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(state = rememberList("live:$group", ready = channels != null), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list, key = { it.ch.id }) { c -> ChannelCard(core, c, onClick = { scope.launch { playChannel(core, player, c, null) } }, onMore = { menu = c }) }
            }
        }
    }
    if (picker) Options("Groupes", null, pills.map { (name, label) ->
        Option(label + (groups.firstOrNull { it.name == name }?.let { "  ·  ${it.count}" } ?: ""), checked = name == group) { pick(name) }
    }) { picker = false }
    menu?.let { c ->
        Options(c.ch.name, null, listOf(
            Option("Lire") { scope.launch { playChannel(core, player, c, null) } },
            Option(if (c.isFavorite) "Retirer des favoris" else "Ajouter aux favoris") {
                scope.launch { core.library.toggleFavorite(c.ch.playlistId, com.lumentv.data.db.PrefKind.CHANNEL, c.ch.uid); core.library.bump() }
            },
            Option("Voir le programme") { open(Route.GuideChannel(c)) },
            Option(if (c.isHidden) "Afficher la chaîne" else "Masquer la chaîne") {
                scope.launch { core.library.setHidden(c.ch.playlistId, com.lumentv.data.db.PrefKind.CHANNEL, c.ch.uid, !c.isHidden) }
            },
        )) { menu = null }
    }
    if (groupMenu) info?.let { g ->
        Options(g.name, "${g.count} chaînes", listOf(
            Option(if (g.hidden) "Afficher le groupe" else "Masquer le groupe") {
                scope.launch {
                    core.library.setHidden(p.id, com.lumentv.data.db.PrefKind.GROUP, g.name, !g.hidden)
                    if (!g.hidden && !showHidden) pick(Groups.ALL)
                }
            },
            Option(if (g.locked) "Retirer le verrou parental" else "Verrouiller par code PIN") {
                scope.launch {
                    when {
                        g.locked -> if (Parental.check(core, "Retirer le verrou de « ${g.name} »")) core.library.setLocked(p.id, g.name, false)
                        core.settings.current().pinHash.isEmpty() -> Parental.notice = "Définis d'abord un code PIN dans Plus › Paramètres › Contrôle parental."
                        else -> { core.library.setLocked(p.id, g.name, true); Parental.relock(p.id, g.name) }
                    }
                }
            },
        )) { groupMenu = false }
    }
}

/** A channel as the Android phone shows it: number, logo, name (★, Rattrapage), what is on and its progress, ⋮. */
@Composable
private fun ChannelCard(core: AppCore, c: ChannelRow, onClick: () -> Unit, onMore: () -> Unit) {
    val now by produceState<com.lumentv.data.db.ProgramEntity?>(null, c.ch.id) { value = runCatching { core.epg.nowNext(c).now }.getOrNull() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Panel).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(c.ch.number.toString(), fontSize = 14.sp, color = Palette.Text, modifier = Modifier.width(34.dp))
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
            AsyncImage(c.ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.ch.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.isFavorite) Text("  ★", fontSize = 14.sp, color = Palette.Gold)
                if (c.isHidden) Text("  masquée", fontSize = 12.sp, color = Palette.TextDim)
                if (c.ch.catchupDays > 0) Text("Rattrapage", fontSize = 11.sp, color = Palette.Text, modifier = Modifier.padding(start = 8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Palette.PanelHigh).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            now?.let { n ->
                Text(n.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val t = nowMillis()
                if (n.stop > n.start) ProgressLine(((t - n.start).toFloat() / (n.stop - n.start)).coerceIn(0f, 1f), Modifier.fillMaxWidth(0.6f).padding(top = 4.dp))
            }
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
            Text("⋮", fontSize = 22.sp, color = Palette.TextDim)
        }
    }
}
