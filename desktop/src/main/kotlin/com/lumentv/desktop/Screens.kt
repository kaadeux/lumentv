@file:OptIn(ExperimentalFoundationApi::class)

package com.lumentv.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lumentv.core.Featured
import com.lumentv.core.normalize
import com.lumentv.core.userMessage
import com.lumentv.sync.SyncHistory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ALL = "\u0000all"
private const val FAVS = "\u0000fav"
private const val RECENT = "\u0000recent"
private val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)

// ---- Playback ----------------------------------------------------------------------------------------------

fun AppState.playChannel(c: Ch) {
    val p = active ?: return
    PlayerWindow.open(this, PlayItem(Kind.CHANNEL, c.uid, c.name, c.group, c.logo, null, library.channelUrl(p, c), live = true, userAgent = p.userAgent))
}

fun AppState.playMovie(m: Mv, fromStart: Boolean = false) {
    val p = active ?: return
    val start = if (fromStart) 0 else progress(Kind.MOVIE, m.uid)?.let { if (it.durationMs > 0 && it.positionMs > it.durationMs - 120_000) 0 else it.positionMs } ?: 0
    PlayerWindow.open(this, PlayItem(Kind.MOVIE, m.uid, m.name, m.year, m.poster, null, library.movieUrl(p, m), live = false, startMs = start, userAgent = p.userAgent))
}

fun AppState.episodeItem(s: Se, eps: List<Ep>, i: Int, fromStart: Boolean = false): PlayItem {
    val p = active!!
    val tmdb = s.tmdb ?: catalog?.series?.firstOrNull { it.uid == s.uid }?.tmdb
    val e = eps[i]
    val start = if (fromStart) 0 else progress(Kind.EPISODE, e.uid)?.let { if (it.durationMs > 0 && it.positionMs > it.durationMs - 120_000) 0 else it.positionMs } ?: 0
    return PlayItem(Kind.EPISODE, e.uid, s.name, "S${e.season} E${e.episode} · ${e.title}", s.cover, s.uid, library.episodeUrl(p, e), live = false,
        startMs = start, userAgent = p.userAgent, tmdb = tmdb, season = e.season, episode = e.episode, next = if (i + 1 < eps.size) ({ episodeItem(s, eps, i + 1, fromStart = true) }) else null)
}

/** Resumes a history entry: finds the channel / film / episode in the current playlist. */
suspend fun AppState.resume(h: SyncHistory) {
    val c = catalog ?: return
    when (h.kind) {
        Kind.CHANNEL -> c.channels.firstOrNull { it.uid == h.uid }?.let { playChannel(it) }
        Kind.MOVIE -> c.movies.firstOrNull { it.uid == h.uid }?.let { playMovie(it) }
        Kind.EPISODE -> {
            val s = c.series.firstOrNull { it.uid == h.parentUid } ?: return show("Série introuvable dans cette playlist.")
            val eps = runCatching { library.episodes(active!!, c, s) }.getOrElse { return show(userMessage(it)) }
            val i = eps.indexOfFirst { it.uid == h.uid }
            if (i >= 0) PlayerWindow.open(this, episodeItem(s, eps, i))
        }
    }
}

// ---- Shared pieces ----------------------------------------------------------------------------------------

@Composable
fun Filter(value: String, placeholder: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, singleLine = true, placeholder = { Text(placeholder) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))

@Composable
fun Categories(title: String, items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.width(260.dp).fillMaxHeight()) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Pal.text, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(items, key = { it.first }) { (id, label) ->
                val sel = id == selected
                Text(label, color = if (sel) Pal.text else Pal.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) Pal.accent.copy(alpha = 0.22f) else Pal.bg)
                        .combinedClickable { onSelect(id) }.padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
    }
}

// ---- Direct -------------------------------------------------------------------------------------------------

@Composable
fun LiveScreen(state: AppState) {
    val c = state.catalog!!
    var group by remember { mutableStateOf(ALL) }
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Ch?>(null) }
    val groups = remember(c) { c.channels.groupBy { it.group }.map { (g, l) -> g to "$g  (${l.size})" } }
    val favUids = remember(state.doc.favorites, state.activeKey) { state.favorites(Kind.CHANNEL).map { it.uid } }
    val list = remember(c, group, filter, favUids) {
        val base = when (group) { ALL -> c.channels; FAVS -> favUids.mapNotNull { u -> c.channels.firstOrNull { it.uid == u } }; else -> c.channels.filter { it.group == group } }
        val f = normalize(filter)
        if (f.isEmpty()) base else base.filter { it.norm.contains(f) }
    }
    val now = System.currentTimeMillis()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Categories("Groupes", listOf(ALL to "Toutes les chaînes (${c.channels.size})", FAVS to "★ Favoris") + groups, group) { group = it }
        Column(Modifier.weight(1f)) {
            Filter(filter, "Rechercher une chaîne") { filter = it }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list, key = { it.uid }) { ch ->
                    val cur = state.guide[ch.uid]?.firstOrNull { now in it.start until it.stop }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected?.uid == ch.uid) Pal.panelHigh else Pal.panel)
                        .combinedClickable(onDoubleClick = { state.playChannel(ch) }) { selected = ch }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${ch.num}", color = Pal.dim, modifier = Modifier.width(44.dp))
                        AsyncImage(ch.logo, null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Pal.panelHigh), contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, color = Pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (cur != null) {
                                Text(cur.title, color = Pal.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator({ (now - cur.start).toFloat() / (cur.stop - cur.start) }, Modifier.width(200.dp).height(3.dp).padding(top = 2.dp))
                            }
                        }
                        if (state.isFavorite(Kind.CHANNEL, ch.uid)) Icon(Icons.Filled.Favorite, null, tint = Pal.accent, modifier = Modifier.size(16.dp))
                        IconButton({ state.playChannel(ch) }) { Icon(Icons.Filled.PlayArrow, "Regarder", tint = Pal.text) }
                    }
                }
            }
        }
        Column(Modifier.width(360.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Pal.panel).padding(16.dp)) {
            val ch = selected
            if (ch == null) Text("Cliquez sur une chaîne pour voir ses programmes, double-cliquez pour la regarder.", color = Pal.dim)
            else {
                Text(ch.name, style = MaterialTheme.typography.titleLarge, color = Pal.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                    Button({ state.playChannel(ch) }) { Text("▶ Regarder") }
                    OutlinedButton({ state.toggleFavorite(Kind.CHANNEL, ch.uid) }) { Text(if (state.isFavorite(Kind.CHANNEL, ch.uid)) "Retirer des favoris" else "★ Favori") }
                }
                val progs = state.guide[ch.uid].orEmpty().filter { it.stop > now }
                if (progs.isEmpty()) Text(if (state.guide.isEmpty()) "Guide TV en cours de chargement…" else "Pas de programme pour cette chaîne.", color = Pal.dim)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(progs.take(40)) { pr ->
                        Column {
                            Text("${hm.format(Date(pr.start))} – ${hm.format(Date(pr.stop))}" + if (now in pr.start until pr.stop) "  · en cours" else "",
                                color = if (now in pr.start until pr.stop) Pal.accent else Pal.dim, style = MaterialTheme.typography.bodySmall)
                            Text(pr.title, color = Pal.text)
                            pr.description?.takeIf { now in pr.start until pr.stop }?.let { Text(it, color = Pal.dim, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

// ---- Films --------------------------------------------------------------------------------------------------

/** The home (banner and rows); the other entries are grids with the categories on the left. */
private const val HOME = "\u0000home"

@Composable
fun MoviesScreen(state: AppState) {
    val c = state.catalog!!
    val scope = rememberCoroutineScope()
    var cat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(HOME) }
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf(System.getenv("LUMEN_OPEN")?.let { n -> c.movies.firstOrNull { it.name.contains(n, true) } }) }
    // Kept above the film page, which replaces the screen: closing the page returns to the same place (home and grid).
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    TopOnChange(grid, cat)
    // The TMDB rows (same engine as the TV), again when a film is watched or rated.
    val lastMovie = remember(state.doc.history, state.activeKey) { state.historyOf(Kind.MOVIE).firstOrNull()?.uid }
    val rated = remember(state.doc.ratings, state.activeKey) { state.ratings(Kind.MOVIE).map { it.uid to it.stars } }
    val shelves = rememberShelves(c, lastMovie, rated) { state.vod.movieRows(c) }
    val resume = resumeMovies(state)
    var forget by remember { mutableStateOf<ResumeItem?>(null) }
    open?.let { m -> MovieDetail(state, m) { open = null }; return }
    val cats = remember(c) { c.movies.groupBy { it.category }.map { (g, l) -> g to "$g  (${l.size})" } }
    if (cat == HOME) {
        val catRows = remember(c) { categoryRows(c.movies, { it.name }, { it.category }, { it.added }) }
        val rows = buildList {
            if (resume.isNotEmpty()) add(HomeRow("resume", "Reprendre la lecture", resume.mapNotNull { r ->
                val m = c.movies.firstOrNull { it.uid == r.key } ?: return@mapNotNull null
                val base = state.movieTile("resume", m) { open = it }
                HomeTile(base.key, base.title, r.image ?: m.poster, r.line, r.progress, false,
                    base.hero.playing("Reprendre") { scope.launch { r.play() } }, { scope.launch { r.play() } }, onRemove = { forget = r })
            }))
            shelves.distinctBy { it.title }.forEach { sh -> add(HomeRow("shelf:" + sh.title, sh.title, sh.items.map { state.movieTile("f:" + sh.title, it) { m -> open = m } })) }
            catRows.forEach { (name, list) -> add(HomeRow("cat:$name", name, list.map { state.movieTile("c:$name", it) { m -> open = m } })) }
        }
        pages.SaveableStateProvider("home") {
            VodHome(state, rows, androidx.compose.foundation.lazy.rememberLazyListState()) {
                MenuPill("Catégories  ▾", listOf<Pair<String, () -> Unit>>("Tous les films" to { cat = ALL }, "★ Favoris" to { cat = FAVS }) +
                    cats.map { (id, label) -> label to { cat = id } })
                Pill("Tous les films", { cat = ALL })
            }
        }
        forget?.let { r -> ForgetDialog(r.title, false, { state.forgetHistory(r.kind, r.forget) }) { forget = null } }
        return
    }
    val favUids = remember(state.doc.favorites, state.activeKey) { state.favorites(Kind.MOVIE).map { it.uid } }
    val list = remember(c, cat, filter, favUids) {
        val base = when (cat) {
            RECENT -> c.movies.sortedByDescending { it.added }.take(300)
            ALL -> c.movies
            FAVS -> favUids.mapNotNull { u -> c.movies.firstOrNull { it.uid == u } }
            else -> c.movies.filter { it.category == cat }
        }
        val f = normalize(filter); if (f.isEmpty()) base else base.filter { it.norm.contains(f) }
    }
    pages.SaveableStateProvider("grid") {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Categories("Films", listOf(HOME to "Accueil des films", RECENT to "Ajoutés récemment", ALL to "Tous les films (${c.movies.size})", FAVS to "★ Favoris") + cats, cat) { cat = it }
            Column(Modifier.weight(1f)) {
                Filter(filter, if (cat == ALL || cat == RECENT) "Rechercher un film" else "Rechercher dans cette liste") { filter = it }
                LazyVerticalGrid(GridCells.Adaptive(150.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(list, key = { it.uid }) { m ->
                        Poster(Featured.shortTitle(m.name), m.poster, Featured.posterLine(m.year, m.rating, m.name), ratio(state.progress(Kind.MOVIE, m.uid)),
                            state.isFavorite(Kind.MOVIE, m.uid)) { open = m }
                    }
                }
            }
        }
    }
}

// ---- Séries -------------------------------------------------------------------------------------------------

@Composable
fun SeriesScreen(state: AppState) {
    val c = state.catalog!!
    val scope = rememberCoroutineScope()
    var cat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(HOME) }
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // LUMEN_OPEN=<name> opens that series at launch (used for screenshots).
    var open by remember { mutableStateOf(System.getenv("LUMEN_OPEN")?.let { n -> c.series.firstOrNull { it.name.equals(n, true) } }) }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    TopOnChange(grid, cat)
    val lastSeries = remember(state.doc.history, state.activeKey) { state.historyOf(Kind.EPISODE).firstOrNull()?.parentUid }
    val rated = remember(state.doc.ratings, state.activeKey) { state.ratings(Kind.SERIES).map { it.uid to it.stars } }
    val shelves = rememberShelves(c, lastSeries, rated) { state.vod.seriesRows(c) }
    val resume = resumeSeries(state) { open = it }
    var forget by remember { mutableStateOf<ResumeItem?>(null) }
    open?.let { s -> SeriesDetail(state, s) { open = null }; return }
    val cats = remember(c) { c.series.groupBy { it.category }.map { (g, l) -> g to "$g  (${l.size})" } }
    if (cat == HOME) {
        val catRows = remember(c) { categoryRows(c.series, { it.name }, { it.category }, { it.updated }) }
        val rows = buildList {
            if (resume.isNotEmpty()) add(HomeRow("resume", "Reprendre la lecture", resume.mapNotNull { r ->
                val s = c.series.firstOrNull { it.uid == r.key } ?: return@mapNotNull null
                val base = state.seriesTile("resume", s) { open = it }
                HomeTile(base.key, base.title, r.image ?: s.cover, r.line, r.progress, false,
                    base.hero.playing("Reprendre") { scope.launch { r.play() } }, { scope.launch { r.play() } }, onRemove = { forget = r })
            }))
            shelves.distinctBy { it.title }.forEach { sh -> add(HomeRow("shelf:" + sh.title, sh.title, sh.items.map { state.seriesTile("f:" + sh.title, it) { s -> open = s } })) }
            catRows.forEach { (name, list) -> add(HomeRow("cat:$name", name, list.map { state.seriesTile("c:$name", it) { s -> open = s } })) }
        }
        pages.SaveableStateProvider("home") {
            VodHome(state, rows, androidx.compose.foundation.lazy.rememberLazyListState()) {
                MenuPill("Catégories  ▾", listOf<Pair<String, () -> Unit>>("Toutes les séries" to { cat = ALL }, "Ajoutées récemment" to { cat = RECENT }, "★ Favoris" to { cat = FAVS }) +
                    cats.map { (id, label) -> label to { cat = id } })
                Pill("Ajoutées récemment", { cat = RECENT })
            }
        }
        forget?.let { r -> ForgetDialog(r.title, true, { state.forgetHistory(r.kind, r.forget) }) { forget = null } }
        return
    }
    val favUids = remember(state.doc.favorites, state.activeKey) { state.favorites(Kind.SERIES).map { it.uid } }
    val list = remember(c, cat, filter, favUids) {
        val base = when (cat) {
            ALL -> c.series
            RECENT -> c.series.sortedByDescending { it.updated }.take(300)
            FAVS -> favUids.mapNotNull { u -> c.series.firstOrNull { it.uid == u } }
            else -> c.series.filter { it.category == cat }
        }
        val f = normalize(filter); if (f.isEmpty()) base else base.filter { it.norm.contains(f) }
    }
    pages.SaveableStateProvider("grid") {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Categories("Séries", listOf(HOME to "Accueil des séries", ALL to "Toutes les séries (${c.series.size})", RECENT to "Ajoutées récemment", FAVS to "★ Favoris") + cats, cat) { cat = it }
            Column(Modifier.weight(1f)) {
                Filter(filter, if (cat == ALL || cat == RECENT) "Rechercher une série" else "Rechercher dans cette liste") { filter = it }
                LazyVerticalGrid(GridCells.Adaptive(150.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(list, key = { it.uid }) { s ->
                        Poster(Featured.shortTitle(s.name), s.cover, Featured.posterLine(s.year, s.rating, null), null, state.isFavorite(Kind.SERIES, s.uid)) { open = s }
                    }
                }
            }
        }
    }
}

// ---- Recherche, favoris, historique -------------------------------------------------------------------------

/**
 * Channels, films and series by a tolerant search (typos, accents, "spiderman" for "Spider-Man"), plus the titles TMDB
 * knows under another name and the films and series of an actor, a director or a creator: the same search as the TV.
 */
@Composable
fun SearchScreen(state: AppState) {
    val c = state.catalog!!
    var q by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var openMovie by remember { mutableStateOf<Mv?>(null) }
    var openSeries by remember { mutableStateOf<Se?>(null) }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // Asked a moment after the last key, the previous results shown meanwhile.
    val results by produceState<Pair<String, com.lumentv.data.discover.TitleSearch.Results>?>(null, q, c) {
        if (q.trim().length < 2) { value = null; return@produceState }
        kotlinx.coroutines.delay(300)
        value = q to state.vod.search(q, c)
    }
    val idx = remember(c) { state.vod.index(c) }
    openSeries?.let { s -> SeriesDetail(state, s) { openSeries = null }; return }
    openMovie?.let { m -> MovieDetail(state, m) { openMovie = null }; return }
    pages.SaveableStateProvider("results") {
        Column(Modifier.fillMaxSize()) {
            Filter(q, "Chaîne, film, série, acteur, réalisateur…") { q = it }
            if (q.trim().length < 2) { Text("Tapez au moins 2 lettres.", color = Pal.dim); return@Column }
            val chs = remember(idx, q) { com.lumentv.core.SmartSearch.search(com.lumentv.core.SmartSearch.Query(q), idx.channels, 40) }
            val r = results?.second
            val mvs = r?.movies.orEmpty().mapNotNull(idx::movie)
            val ses = r?.series.orEmpty().mapNotNull(idx::series)
            if (r != null && chs.isEmpty() && mvs.isEmpty() && ses.isEmpty() && r.people.isEmpty()) { Text("Rien ne correspond à « $q ».", color = Pal.dim); return@Column }
            LazyColumn(state = androidx.compose.foundation.lazy.rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (chs.isNotEmpty()) item { SectionTitle("Chaînes · ${chs.size}") }
                items(chs, key = { "c" + it.uid }) { ch -> ChannelLine(state, ch) }
                if (mvs.isNotEmpty()) item(key = "mv") { SectionTitle("Films · ${mvs.size}"); MovieRow(state, mvs) { openMovie = it } }
                if (ses.isNotEmpty()) item(key = "se") { SectionTitle("Séries · ${ses.size}"); SeriesRow(state, ses) { openSeries = it } }
                r?.people.orEmpty().forEach { h ->
                    val pm = h.movies.mapNotNull(idx::movie); val ps = h.series.mapNotNull(idx::series)
                    item(key = "p" + h.key) {
                        SectionTitle("${h.title} · ${pm.size + ps.size}")
                        if (pm.isNotEmpty()) MovieRow(state, pm) { openMovie = it }
                        if (ps.isNotEmpty()) SeriesRow(state, ps) { openSeries = it }
                    }
                }
                if (r == null) item { Text("Recherche…", color = Pal.dim) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = Pal.text, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun ChannelLine(state: AppState, ch: Ch, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Pal.panel).combinedClickable { state.playChannel(ch) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(ch.logo, null, Modifier.size(36.dp), contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
        Text(ch.name, color = Pal.text, modifier = Modifier.weight(1f)); Text(ch.group, color = Pal.dim)
        onRemove?.let { TextButton(it) { Text("Retirer") } }
    }
}

@Composable
private fun MovieRow(state: AppState, list: List<Mv>, open: (Mv) -> Unit) =
    ScrollRow(Modifier.padding(vertical = 6.dp)) {
        items(list, key = { it.uid }) { m ->
            Box(Modifier.width(140.dp)) {
                Poster(Featured.shortTitle(m.name), m.poster, Featured.posterLine(m.year, m.rating, m.name), ratio(state.progress(Kind.MOVIE, m.uid)), state.isFavorite(Kind.MOVIE, m.uid)) { open(m) }
            }
        }
    }

@Composable
private fun SeriesRow(state: AppState, list: List<Se>, open: (Se) -> Unit) =
    ScrollRow(Modifier.padding(vertical = 6.dp)) {
        items(list, key = { it.uid }) { s ->
            Box(Modifier.width(140.dp)) { Poster(Featured.shortTitle(s.name), s.cover, Featured.posterLine(s.year, s.rating, null), null, state.isFavorite(Kind.SERIES, s.uid)) { open(s) } }
        }
    }

@Composable
fun FavoritesScreen(state: AppState) {
    val c = state.catalog!!
    var openMovie by remember { mutableStateOf<Mv?>(null) }
    var openSeries by remember { mutableStateOf<Se?>(null) }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    openSeries?.let { s -> SeriesDetail(state, s) { openSeries = null }; return }
    openMovie?.let { m -> MovieDetail(state, m) { openMovie = null }; return }
    val chs = state.favorites(Kind.CHANNEL).mapNotNull { f -> c.channels.firstOrNull { it.uid == f.uid } }
    val mvs = state.favorites(Kind.MOVIE).mapNotNull { f -> c.movies.firstOrNull { it.uid == f.uid } }
    val ses = state.favorites(Kind.SERIES).mapNotNull { f -> c.series.firstOrNull { it.uid == f.uid } }
    if (chs.isEmpty() && mvs.isEmpty() && ses.isEmpty()) return Empty("Aucun favori", "Ajoutez des chaînes, films ou séries avec ★. Ils sont partagés avec la TV par votre profil.")
    pages.SaveableStateProvider("list") {
        LazyColumn(state = androidx.compose.foundation.lazy.rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Favoris", style = MaterialTheme.typography.headlineSmall, color = Pal.text) }
            if (chs.isNotEmpty()) item { SectionTitle("Chaînes") }
            items(chs, key = { "c" + it.uid }) { ch -> ChannelLine(state, ch) { state.toggleFavorite(Kind.CHANNEL, ch.uid) } }
            if (mvs.isNotEmpty()) item(key = "mv") { SectionTitle("Films"); MovieRow(state, mvs) { openMovie = it } }
            if (ses.isNotEmpty()) item(key = "se") { SectionTitle("Séries"); SeriesRow(state, ses) { openSeries = it } }
        }
    }
}

@Composable
fun HistoryScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val list = state.history()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Historique", style = MaterialTheme.typography.headlineSmall, color = Pal.text, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) OutlinedButton({ state.clearHistory() }) { Text("Effacer l'historique") }
        }
        Text("Partagé avec vos autres appareils par le profil en ligne : reprenez ici ce que vous avez commencé sur la TV.", color = Pal.dim, modifier = Modifier.padding(vertical = 8.dp))
        if (list.isEmpty()) { Empty("Rien pour l'instant", ""); return@Column }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.kind + it.uid }) { h ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Pal.panel).combinedClickable { scope.launch { state.resume(h) } }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(h.image, null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Pal.panelHigh), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
                    Column(Modifier.weight(1f)) {
                        Text(h.title, color = Pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(when (h.kind) { Kind.CHANNEL -> "Chaîne"; Kind.MOVIE -> "Film"; else -> "Épisode" }, h.subtitle, fmt.format(Date(h.watchedAt))).joinToString(" · "),
                            color = Pal.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        ratio(h)?.let { LinearProgressIndicator({ it }, Modifier.width(260.dp).height(3.dp).padding(top = 4.dp)) }
                    }
                    Icon(Icons.Filled.PlayArrow, null, tint = Pal.text)
                }
            }
        }
    }
}


// ---- Reprendre la lecture ----------------------------------------------------------------------------------

/** One poster of the "Reprendre la lecture" row ([key]: the film or the series); [forget] are the history uids removed with it. */
private class ResumeItem(val key: String, val title: String, val line: String?, val image: String?, val progress: Float?,
                         val play: suspend () -> Unit, val kind: String, val forget: List<String>)

/** Films started and not finished, the last watched first. */
@Composable
private fun resumeMovies(state: AppState): List<ResumeItem> = remember(state.doc.history, state.activeKey) {
    state.historyOf(Kind.MOVIE).filter { com.lumentv.core.Resume.movieToResume(it.positionMs, it.durationMs) }.take(com.lumentv.core.Resume.LIMIT).map { h ->
        ResumeItem(h.uid, h.title, com.lumentv.core.Resume.remaining(h.positionMs, h.durationMs), h.image, ratio(h), { state.resume(h) }, Kind.MOVIE, listOf(h.uid))
    }
}

/**
 * Series to go on with, the last watched first: the episode left halfway, or the next one when the last was seen to
 * the end. A series whose last episode was its final one is done and leaves the row.
 */
@Composable
private fun resumeSeries(state: AppState, onOpen: (Se) -> Unit): List<ResumeItem> {
    val c = state.catalog ?: return emptyList()
    val items by produceState(emptyList<ResumeItem>(), state.doc.history, state.activeKey, c) {
        value = state.historyOf(Kind.EPISODE).filter { it.parentUid != null }.groupBy { it.parentUid!! }.values
            .map { eps -> eps.first() to eps.map { it.uid } }
            .mapNotNull { (h, uids) ->
                val s = c.series.firstOrNull { it.uid == h.parentUid } ?: return@mapNotNull null
                if (!com.lumentv.core.Resume.watched(h.positionMs, h.durationMs)) {
                    ResumeItem(s.uid, s.name, listOfNotNull(h.subtitle?.substringBefore(" · "), com.lumentv.core.Resume.remaining(h.positionMs, h.durationMs)).joinToString(" · "),
                        s.cover ?: h.image, ratio(h), { state.resume(h) }, Kind.EPISODE, uids)
                } else {
                    val eps = runCatching { state.library.episodes(state.active!!, c, s) }.getOrNull()
                    val i = eps?.indexOfFirst { it.uid == h.uid } ?: -1
                    when {
                        eps == null || i < 0 -> ResumeItem(s.uid, s.name, "Continuer", s.cover ?: h.image, null, { onOpen(s) }, Kind.EPISODE, uids)
                        i + 1 < eps.size -> eps[i + 1].let { n ->
                            ResumeItem(s.uid, s.name, "Suivant : S${n.season} E${n.episode}", s.cover ?: h.image, null,
                                { PlayerWindow.open(state, state.episodeItem(s, eps, i + 1, fromStart = true)) }, Kind.EPISODE, uids)
                        }
                        else -> null
                    }
                }
            }.take(com.lumentv.core.Resume.LIMIT)
    }
    return items
}

/** One row per category of the playlist (the first ten, without foreign ones): one copy per title, newest first. */
private fun <T> categoryRows(list: List<T>, name: (T) -> String, category: (T) -> String, added: (T) -> Long): List<Pair<String, List<T>>> =
    list.groupBy(category).filterKeys { !Featured.foreign("", it) }.entries.take(10)
        .map { (cat, items) -> cat to Featured.clean(items.sortedByDescending(added), name, category) }.filter { it.second.size >= 3 }

/** Another category starts at its top; coming back to the section (same category) keeps the place. */
@Composable
private fun TopOnChange(grid: androidx.compose.foundation.lazy.grid.LazyGridState, cat: String) {
    var shown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(cat) }
    LaunchedEffect(cat) { if (cat != shown) { shown = cat; grid.scrollToItem(0) } }
}
