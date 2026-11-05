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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.AppCore
import com.lumentv.core.Featured
import com.lumentv.core.SecretBox
import com.lumentv.core.nowMillis
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.StreamSpec
import com.lumentv.data.settings.Keys
import com.lumentv.data.xtream.XtreamApi
import kotlinx.coroutines.launch

/** Where the viewer is, above the bottom tabs. */
internal sealed interface Route {
    data class Movie(val row: MovieRow) : Route
    data class Series(val row: SeriesRow) : Route
    data class Group(val name: String) : Route
    data class Categories(val series: Boolean) : Route
    data class Person(val hit: com.lumentv.data.repo.PersonHit) : Route
}

private enum class Tab(val label: String, val icon: String) { MOVIES("Films", "🎬"), SERIES("Séries", "📺"), LIVE("TV", "📡"), SEARCH("Recherche", "🔍"), PROFILE("Profil", "👤") }

/**
 * The iPhone app (and, step by step, the Android phone's): the account to add first, then Films, Séries and TV over
 * the shared database. Playback goes to the platform's [player].
 */
@Composable
fun LumenPhoneApp(core: AppCore, player: VideoPlayer) = LumenTheme {
    val playlists by core.playlists.observe().collectAsState(null)
    val settings by core.settings.flow.collectAsState(null)
    val list = playlists
    val s = settings
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when {
            list == null || s == null -> Centered { CircularProgressIndicator() }
            list.isEmpty() -> SetupScreen(core)
            else -> {
                val active = list.firstOrNull { it.id == s.activePlaylistId } ?: list.first()
                if (active.lastRefresh == 0L) FirstLoad(core, active) else MainScreen(core, active, player)
            }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }

// ---- First start: the Xtream account ----------------------------------------------------------------------------

@Composable
private fun SetupScreen(core: AppCore) {
    var server by remember { mutableStateOf("http://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("lumenTV", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Ton compte Xtream. Il reste sur cet iPhone, chiffré.", color = Palette.TextDim)
        Spacer(Modifier.height(24.dp))
        ExactField(server, { server = it }, "Serveur (http://…:port)", keyboard = KeyboardType.Uri)
        ExactField(user, { user = it }, "Identifiant")
        ExactField(pass, { pass = it }, "Mot de passe", secret = true)
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Palette.Danger) }
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            if (server.length < 10 || user.isBlank() || pass.isBlank()) { error = "Remplis les trois champs."; return@Button }
            scope.launch {
                val src = XtreamApi.normalizeServer(server.trim())
                val id = core.playlists.save(PlaylistEntity(
                    name = src.substringAfter("://").substringBefore(':').substringBefore('/'), type = PlaylistType.XTREAM,
                    sourceEnc = SecretBox.encrypt(src), username = user.trim(), passwordEnc = SecretBox.encrypt(pass),
                ))
                core.settings.set(Keys.activePlaylist, id)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Se connecter") }
    }
}

/** The list is read once after the account is added (a minute or two for 30 000 titles). */
@Composable
private fun FirstLoad(core: AppCore, p: PlaylistEntity) {
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
                OutlinedButton(onClick = { scope.launch { core.playlists.delete(p.id) } }) { Text("Changer de compte") }
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
    Scaffold(
        containerColor = Palette.Background,
        bottomBar = {
            NavigationBar(containerColor = Palette.Panel) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t; stack.clear() },
                        icon = { Text(t.icon, fontSize = 20.sp) }, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val r = stack.lastOrNull()) {
                is Route.Movie -> MoviePage(core, r.row, player, back)
                is Route.Series -> SeriesPage(core, p, r.row, player, back)
                is Route.Group -> ChannelsPage(core, p, r.name, player, back)
                is Route.Person -> PersonPage(r.hit, open, back)
                is Route.Categories -> if (r.series) SeriesCategories(core, p, open, back) else MovieCategories(core, p, open, back)
                null -> when (tab) {
                    Tab.MOVIES -> MoviesHome(core, p, player, open)
                    Tab.SERIES -> SeriesHome(core, p, player, open)
                    Tab.LIVE -> LiveHome(core, p, open)
                    Tab.SEARCH -> SearchHome(core, p, player, open)
                    Tab.PROFILE -> ProfileHome(core, p)
                }
            }
        }
    }
}

@Composable
internal fun Header(title: String, back: (() -> Unit)? = null) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
) {
    if (back != null) Text("‹", fontSize = 30.sp, modifier = Modifier.clickable(onClick = back).padding(end = 12.dp))
    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Chips(names: List<String>, selected: String?, onPick: (String) -> Unit) = LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    items(names) { n -> FilterChip(selected = n == selected, onClick = { onPick(n) }, label = { Text(n, maxLines = 1) }) }
}

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
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items) { h ->
            Box(Modifier.width(110.dp)) {
                Poster(h.image, Featured.shortTitle(h.title) + (h.subtitle?.substringBefore(" · ")?.let { " · $it" } ?: ""), h.positionMs.toFloat() / h.durationMs) { onOpen(h) }
            }
        }
    }
}

@Composable
private fun MovieCategories(core: AppCore, p: PlaylistEntity, open: (Route) -> Unit, back: () -> Unit) {
    val cats by produceState(emptyList<String>(), p.id, p.lastRefresh) {
        value = listOf(RECENT) + core.library.movieCategories(p.id, false).map { it.category }.filter { !Featured.foreign("", it) }
    }
    var cat by remember(p.id) { mutableStateOf(RECENT) }
    val movies by produceState(emptyList<MovieRow>(), p.id, cat, p.lastRefresh) {
        value = oneEach(if (cat == RECENT) core.library.recentMovies(p.id) else core.library.movies(p.id, cat), { it.m.name }, { it.m.category })
    }
    Column {
        Header("Films · catégories", back)
        Chips(cats, cat) { cat = it }
        LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(movies, key = { it.m.id }) { m -> Poster(m.m.poster, Featured.shortTitle(m.m.name)) { open(Route.Movie(m)) } }
        }
    }
}

@Composable
private fun SeriesCategories(core: AppCore, p: PlaylistEntity, open: (Route) -> Unit, back: () -> Unit) {
    val cats by produceState(emptyList<String>(), p.id, p.lastRefresh) {
        value = listOf(RECENT) + core.library.seriesCategories(p.id, false).map { it.category }.filter { !Featured.foreign("", it) }
    }
    var cat by remember(p.id) { mutableStateOf(RECENT) }
    val series by produceState(emptyList<SeriesRow>(), p.id, cat, p.lastRefresh) {
        value = oneEach(if (cat == RECENT) core.library.recentSeries(p.id) else core.library.series(p.id, cat), { it.s.name }, { it.s.category })
    }
    Column {
        Header("Séries · catégories", back)
        Chips(cats, cat) { cat = it }
        LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(series, key = { it.s.id }) { s -> Poster(s.s.cover, Featured.shortTitle(s.s.name)) { open(Route.Series(s)) } }
        }
    }
}

// ---- Pages ------------------------------------------------------------------------------------------------------

internal fun minutes(ms: Long) = (ms / 60_000).let { if (it >= 60) "${it / 60} h ${(it % 60).toString().padStart(2, '0')}" else "$it min" }

internal fun play(core: AppCore, player: VideoPlayer, spec: StreamSpec, title: String, subtitle: String?, startMs: Long, onFinish: (Long, Long) -> Unit) =
    player.play(PlayRequest(spec.url, spec.userAgent, spec.referer, title, subtitle, startMs, spec.live, onFinish))

// ---- Live -------------------------------------------------------------------------------------------------------

@Composable
private fun LiveHome(core: AppCore, p: PlaylistEntity, open: (Route) -> Unit) {
    val groups by produceState(emptyList<com.lumentv.data.repo.GroupInfo>(), p.id, p.lastRefresh) { value = core.library.groups(p.id, false) }
    LazyColumn {
        item { Header("TV") }
        items(groups, key = { it.name }) { g ->
            Row(Modifier.fillMaxWidth().clickable { open(Route.Group(g.name)) }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(g.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${g.count}", color = Palette.TextDim)
            }
        }
    }
}

@Composable
private fun ChannelsPage(core: AppCore, p: PlaylistEntity, group: String, player: VideoPlayer, back: () -> Unit) {
    val settings by core.settings.flow.collectAsState(null)
    val channels by produceState(emptyList<ChannelRow>(), p.id, group) { value = core.library.channels(p.id, group, false, settings?.sort ?: "provider") }
    val scope = rememberCoroutineScope()
    LazyColumn {
        item { Header(group, back) }
        items(channels, key = { it.ch.id }) { c ->
            val now by produceState<String?>(null, c.ch.id) { value = runCatching { core.epg.nowNext(c).now?.title }.getOrNull() }
            Row(Modifier.fillMaxWidth().clickable {
                scope.launch {
                    val spec = core.resolver.channel(c.ch)
                    core.library.record(HistoryEntity(LibraryRepository.key(HistoryKind.CHANNEL, c.ch.playlistId, c.ch.uid), HistoryKind.CHANNEL,
                        c.ch.playlistId, c.ch.uid, c.ch.name, c.ch.groupName, c.ch.logo, watchedAt = nowMillis()))
                    play(core, player, spec, c.ch.name, now, 0) { _, _ -> }
                }
            }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
                    AsyncImage(c.ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(c.ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    now?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}
