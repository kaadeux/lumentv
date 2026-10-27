@file:OptIn(ExperimentalFoundationApi::class)

package com.lumentv.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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

@Composable
fun Poster(title: String, image: String?, caption: String?, progress: Float?, favorite: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Pal.panel).combinedClickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Pal.panelHigh)) {
            AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (favorite) Icon(Icons.Filled.Favorite, null, tint = Pal.accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
        }
        progress?.let { LinearProgressIndicator({ it.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(3.dp)) }
        Text(title, color = Pal.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp))
        Text(caption ?: "", color = Pal.dim, maxLines = 1, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
    }
}

private fun ratio(h: SyncHistory?) = h?.takeIf { it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs }

// ---- Direct -------------------------------------------------------------------------------------------------

@Composable
fun LiveScreen(state: AppState) {
    val c = state.catalog!!
    var group by remember { mutableStateOf(ALL) }
    var filter by remember { mutableStateOf("") }
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
                        AsyncImage(ch.logo, null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Pal.panelHigh), contentScale = ContentScale.Fit)
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

@Composable
fun MoviesScreen(state: AppState) {
    val c = state.catalog!!
    var cat by remember { mutableStateOf(RECENT) }
    var filter by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(System.getenv("LUMEN_OPEN")?.let { n -> c.movies.firstOrNull { it.name.contains(n, true) } }) }
    open?.let { m -> MovieDetail(state, m) { open = null }; return }
    val cats = remember(c) { c.movies.groupBy { it.category }.map { (g, l) -> g to "$g  (${l.size})" } }
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
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Categories("Films", listOf(RECENT to "Ajoutés récemment", ALL to "Tous les films (${c.movies.size})", FAVS to "★ Favoris") + cats, cat) { cat = it }
        Column(Modifier.weight(1f)) {
            Filter(filter, "Rechercher un film") { filter = it }
            LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(list, key = { it.uid }) { m ->
                    Poster(m.name, m.poster, listOfNotNull(m.year, m.rating?.let { "★ $it" }).joinToString(" · "), ratio(state.progress(Kind.MOVIE, m.uid)),
                        state.isFavorite(Kind.MOVIE, m.uid)) { open = m }
                }
            }
        }
    }
}

// ---- Séries -------------------------------------------------------------------------------------------------

@Composable
fun SeriesScreen(state: AppState) {
    val c = state.catalog!!
    var cat by remember { mutableStateOf(ALL) }
    var filter by remember { mutableStateOf("") }
    // LUMEN_OPEN=<name> opens that series at launch (used for screenshots).
    var open by remember { mutableStateOf(System.getenv("LUMEN_OPEN")?.let { n -> c.series.firstOrNull { it.name.equals(n, true) } }) }
    open?.let { s -> SeriesDetail(state, s) { open = null }; return }
    val cats = remember(c) { c.series.groupBy { it.category }.map { (g, l) -> g to "$g  (${l.size})" } }
    val favUids = remember(state.doc.favorites, state.activeKey) { state.favorites(Kind.SERIES).map { it.uid } }
    val list = remember(c, cat, filter, favUids) {
        val base = when (cat) { ALL -> c.series; FAVS -> favUids.mapNotNull { u -> c.series.firstOrNull { it.uid == u } }; else -> c.series.filter { it.category == cat } }
        val f = normalize(filter); if (f.isEmpty()) base else base.filter { it.norm.contains(f) }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Categories("Séries", listOf(ALL to "Toutes les séries (${c.series.size})", FAVS to "★ Favoris") + cats, cat) { cat = it }
        Column(Modifier.weight(1f)) {
            Filter(filter, "Rechercher une série") { filter = it }
            LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(list, key = { it.uid }) { s ->
                    Poster(s.name, s.cover, listOfNotNull(s.year, s.rating?.let { "★ $it" }).joinToString(" · "), null, state.isFavorite(Kind.SERIES, s.uid)) { open = s }
                }
            }
        }
    }
}

// ---- Recherche, favoris, historique -------------------------------------------------------------------------

@Composable
fun SearchScreen(state: AppState) {
    val c = state.catalog!!
    var q by remember { mutableStateOf("") }
    var openMovie by remember { mutableStateOf<Mv?>(null) }
    var openSeries by remember { mutableStateOf<Se?>(null) }
    openSeries?.let { s -> SeriesDetail(state, s) { openSeries = null }; return }
    openMovie?.let { m -> MovieDetail(state, m) { openMovie = null }; return }
    val f = normalize(q)
    Column(Modifier.fillMaxSize()) {
        Filter(q, "Chaîne, film ou série…") { q = it }
        if (f.length < 2) { Text("Tapez au moins 2 lettres.", color = Pal.dim); return@Column }
        val chs = remember(c, f) { c.channels.filter { it.norm.contains(f) }.take(40) }
        val mvs = remember(c, f) { c.movies.filter { it.norm.contains(f) }.take(60) }
        val ses = remember(c, f) { c.series.filter { it.norm.contains(f) }.take(60) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chs.isNotEmpty()) item { Text("Chaînes (${chs.size})", style = MaterialTheme.typography.titleMedium, color = Pal.text) }
            items(chs, key = { "c" + it.uid }) { ch ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Pal.panel).combinedClickable { state.playChannel(ch) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(ch.logo, null, Modifier.size(36.dp), contentScale = ContentScale.Fit)
                    Text(ch.name, color = Pal.text, modifier = Modifier.weight(1f)); Text(ch.group, color = Pal.dim)
                }
            }
            if (mvs.isNotEmpty()) item { Text("Films (${mvs.size})", style = MaterialTheme.typography.titleMedium, color = Pal.text) }
            if (mvs.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(mvs, key = { it.uid }) { m -> Box(Modifier.width(140.dp)) { Poster(m.name, m.poster, m.year, ratio(state.progress(Kind.MOVIE, m.uid)), false) { openMovie = m } } }
                }
            }
            if (ses.isNotEmpty()) item { Text("Séries (${ses.size})", style = MaterialTheme.typography.titleMedium, color = Pal.text) }
            if (ses.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ses, key = { it.uid }) { s -> Box(Modifier.width(140.dp)) { Poster(s.name, s.cover, s.year, null, false) { openSeries = s } } }
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(state: AppState) {
    val c = state.catalog!!
    var openMovie by remember { mutableStateOf<Mv?>(null) }
    var openSeries by remember { mutableStateOf<Se?>(null) }
    openSeries?.let { s -> SeriesDetail(state, s) { openSeries = null }; return }
    openMovie?.let { m -> MovieDetail(state, m) { openMovie = null }; return }
    val chs = state.favorites(Kind.CHANNEL).mapNotNull { f -> c.channels.firstOrNull { it.uid == f.uid } }
    val mvs = state.favorites(Kind.MOVIE).mapNotNull { f -> c.movies.firstOrNull { it.uid == f.uid } }
    val ses = state.favorites(Kind.SERIES).mapNotNull { f -> c.series.firstOrNull { it.uid == f.uid } }
    if (chs.isEmpty() && mvs.isEmpty() && ses.isEmpty()) return Empty("Aucun favori", "Ajoutez des chaînes, films ou séries avec ★. Ils sont partagés avec la TV par votre profil.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Favoris", style = MaterialTheme.typography.headlineSmall, color = Pal.text) }
        if (chs.isNotEmpty()) item { Text("Chaînes", style = MaterialTheme.typography.titleMedium, color = Pal.text) }
        items(chs, key = { "c" + it.uid }) { ch ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Pal.panel).combinedClickable { state.playChannel(ch) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(ch.logo, null, Modifier.size(36.dp), contentScale = ContentScale.Fit)
                Text(ch.name, color = Pal.text, modifier = Modifier.weight(1f)); Text(ch.group, color = Pal.dim)
                TextButton({ state.toggleFavorite(Kind.CHANNEL, ch.uid) }) { Text("Retirer") }
            }
        }
        if (mvs.isNotEmpty()) item { Text("Films", style = MaterialTheme.typography.titleMedium, color = Pal.text) }
        if (mvs.isNotEmpty()) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(mvs, key = { it.uid }) { m -> Box(Modifier.width(140.dp)) { Poster(m.name, m.poster, m.year, ratio(state.progress(Kind.MOVIE, m.uid)), true) { openMovie = m } } } } }
        if (ses.isNotEmpty()) item { Text("Séries", style = MaterialTheme.typography.titleMedium, color = Pal.text) }
        if (ses.isNotEmpty()) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(ses, key = { it.uid }) { s -> Box(Modifier.width(140.dp)) { Poster(s.name, s.cover, s.year, null, true) { openSeries = s } } } } }
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
                    AsyncImage(h.image, null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Pal.panelHigh), contentScale = ContentScale.Crop)
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

