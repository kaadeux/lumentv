@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import com.lumentv.ui.components.ButtonRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.core.normalize
import com.lumentv.core.userMessage
import com.lumentv.ui.components.FilterField
import com.lumentv.data.db.CategoryCount
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.db.PrefKind
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.xtream.XVodInfo
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.Screen
import com.lumentv.ui.components.EmptyState
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.Loading
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.Poster
import com.lumentv.ui.components.ProgressLine
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RECENT = "\u0001recent"
private const val ALL = "\u0001all"

@Composable
private fun CategoryColumn(title: String, cats: List<CategoryCount>?, extra: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit,
                           onLong: (String) -> Unit) {
    if (com.lumentv.ui.LocalPhone.current) {
        // Phone: one button showing the category; the list opens in a window.
        var open by remember { mutableStateOf(false) }
        val label = extra.firstOrNull { it.first == selected }?.second ?: selected
        FocusSurface({ open = true }, Modifier.fillMaxWidth().padding(bottom = 8.dp), container = Palette.PanelHigh) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Catégorie", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Changer  ▾", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
            }
        }
        if (open) OptionsDialog(title, extra.map { (id, l) -> MenuOption(l, checked = id == selected) { onSelect(id) } } +
            cats.orEmpty().map { c -> MenuOption("${c.category}  ·  ${c.count}", checked = c.category == selected) { onSelect(c.category) } } +
            listOfNotNull(cats?.firstOrNull { it.category == selected }?.let { MenuOption("⚙  Masquer « ${it.category} »…") { onLong(it.category) } }),
            onDismiss = { open = false })
        return
    }
    Column(Modifier.width(190.dp).fillMaxHeight()) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(extra, key = { it.first }) { (id, label) ->
                FocusSurface({ onSelect(id) }, Modifier.fillMaxWidth(), selected = id == selected, focusedScale = 1.02f) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
            items(cats.orEmpty(), key = { "c:" + it.category }) { c ->
                FocusSurface({ onSelect(c.category) }, Modifier.fillMaxWidth(), onLongClick = { onLong(c.category) }, selected = c.category == selected, focusedScale = 1.02f) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(c.category, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(" ${c.count}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** TV: picker on the left, grid on the right. Phone: picker above, grid below (the grid fills what is left). */
@Composable
private fun VodLayout(content: @Composable () -> Unit) {
    if (com.lumentv.ui.LocalPhone.current) Column(Modifier.fillMaxSize()) { content() }
    else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

/** 5 posters a row on the TV; on a phone as many as fit, about 3 upright and 6 sideways. */
@Composable
private fun posterCells(): GridCells = if (com.lumentv.ui.LocalPhone.current) GridCells.Adaptive(104.dp) else GridCells.Fixed(5)
@Composable
private fun gridGap() = if (com.lumentv.ui.LocalPhone.current) 10.dp else 14.dp

@Composable
private fun PosterTile(title: String, image: String?, subtitle: String?, progress: Float?, favorite: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Column {
        FocusSurface(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), onLongClick = onLong, focusedScale = 1.06f, shape = RoundedCornerShape(8.dp)) {
            Poster(image, title, Modifier.fillMaxSize())
            if (favorite) Text("★", color = Palette.Gold, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (progress != null && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
        }
        Text(title, style = MaterialTheme.typography.bodySmall, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MoviesScreen(vm: MainViewModel) {
    val active by vm.active.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val changes by vm.graph.library.changes.collectAsStateWithLifecycle()
    val history by vm.graph.library.observeHistory(600).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val p = active ?: return EmptyState("Aucune playlist", "")
    var cat by remember(p.id) { mutableStateOf(vm.movieCategory ?: RECENT) }
    val resume = resumeMovies(vm, p.id, history)
    var catMenu by remember { mutableStateOf<String?>(null) }
    val cats by produceState<List<CategoryCount>?>(null, p.id, changes, settings.showHidden) { value = vm.graph.library.movieCategories(p.id, settings.showHidden) }
    val movies by produceState<List<MovieRow>?>(null, p.id, cat, changes) {
        value = null
        value = when (cat) { RECENT -> vm.graph.library.recentMovies(p.id); ALL -> vm.graph.library.movies(p.id, null); else -> vm.graph.library.movies(p.id, cat) }
    }
    val progress = remember(history) { history.filter { it.kind == HistoryKind.MOVIE }.associateBy { it.uid } }
    var filter by remember(p.id, cat) { mutableStateOf("") }

    VodLayout {
        CategoryColumn("Films", cats, listOf(RECENT to "Ajoutés récemment", ALL to "Tous les films"), cat, { cat = it; vm.movieCategory = it }, { catMenu = it })
        Column(Modifier.fillMaxSize()) {
        FilterField(filter, if (cat == ALL || cat == RECENT) "Rechercher un film" else "Rechercher dans « $cat »") { filter = it }
        val list = movies?.let { all -> normalize(filter).let { f -> if (f.isEmpty()) all else all.filter { it.m.nameNorm.contains(f) } } }
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState("Aucun film", if (filter.isBlank()) "Cette catégorie est vide." else "Rien ne correspond à « $filter ».")
            else -> LazyVerticalGrid(posterCells(), horizontalArrangement = Arrangement.spacedBy(gridGap()), verticalArrangement = Arrangement.spacedBy(gridGap()),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 20.dp, start = 6.dp, end = 6.dp),
                modifier = Modifier.fillMaxSize()) {
                if (cat == RECENT && filter.isBlank() && resume.isNotEmpty())
                    item(key = "resume", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { ResumeRow(vm, resume, false, "Ajoutés récemment") }
                items(list, key = { it.m.id }) { r ->
                    val h = progress[r.m.uid]?.takeIf { it.playlistId == p.id }
                    PosterTile(r.m.name, r.m.poster, listOfNotNull(r.m.year, r.m.rating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null },
                        h?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else null }, r.isFavorite,
                        onClick = { vm.open(Screen.MovieDetail(p.id, r.m.uid)) },
                        onLong = { scope.launch { val f = vm.graph.library.toggleFavorite(p.id, PrefKind.MOVIE, r.m.uid); vm.show(if (f) "Ajouté aux favoris" else "Retiré des favoris") } })
                }
            }
        }
        }
    }
    catMenu?.let { c ->
        OptionsDialog(c, listOf(MenuOption("Masquer la catégorie") { scope.launch { vm.graph.library.setHidden(p.id, PrefKind.MOVIE_CATEGORY, c, true) } }),
            onDismiss = { catMenu = null })
    }
}

@Composable
fun SeriesScreen(vm: MainViewModel) {
    val active by vm.active.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val changes by vm.graph.library.changes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val history by vm.graph.library.observeHistory(600).collectAsStateWithLifecycle(emptyList())
    val p = active ?: return EmptyState("Aucune playlist", "")
    var cat by remember(p.id) { mutableStateOf(vm.seriesCategory ?: ALL) }
    val resume = resumeSeries(vm, p.id, history)
    // The row comes a moment after the posters: back to the top, or the grid would stay on its first poster, below the row.
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(resume.isNotEmpty()) { if (resume.isNotEmpty() && grid.firstVisibleItemIndex <= 1) grid.scrollToItem(0) }
    var catMenu by remember { mutableStateOf<String?>(null) }
    val cats by produceState<List<CategoryCount>?>(null, p.id, changes, settings.showHidden) { value = vm.graph.library.seriesCategories(p.id, settings.showHidden) }
    val series by produceState<List<SeriesRow>?>(null, p.id, cat, changes) {
        value = null
        value = vm.graph.library.series(p.id, if (cat == ALL) null else cat)
    }
    var filter by remember(p.id, cat) { mutableStateOf("") }
    VodLayout {
        CategoryColumn("Séries", cats, listOf(ALL to "Toutes les séries"), cat, { cat = it; vm.seriesCategory = it }, { catMenu = it })
        Column(Modifier.fillMaxSize()) {
        FilterField(filter, if (cat == ALL) "Rechercher une série" else "Rechercher dans « $cat »") { filter = it }
        val list = series?.let { all -> normalize(filter).let { f -> if (f.isEmpty()) all else all.filter { it.s.nameNorm.contains(f) } } }
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState("Aucune série", if (filter.isBlank()) "Cette catégorie est vide." else "Rien ne correspond à « $filter ».")
            else -> LazyVerticalGrid(posterCells(), state = grid, horizontalArrangement = Arrangement.spacedBy(gridGap()), verticalArrangement = Arrangement.spacedBy(gridGap()),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 20.dp, start = 6.dp, end = 6.dp),
                modifier = Modifier.fillMaxSize()) {
                if (cat == ALL && filter.isBlank() && resume.isNotEmpty())
                    item(key = "resume", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { ResumeRow(vm, resume, true, "Toutes les séries") }
                items(list, key = { it.s.id }) { r ->
                    PosterTile(r.s.name, r.s.cover, listOfNotNull(r.s.year, r.s.rating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null }, null, r.isFavorite,
                        onClick = { vm.open(Screen.SeriesDetail(p.id, r.s.uid)) },
                        onLong = { scope.launch { val f = vm.graph.library.toggleFavorite(p.id, PrefKind.SERIES, r.s.uid); vm.show(if (f) "Ajoutée aux favoris" else "Retirée des favoris") } })
                }
            }
        }
        }
    }
    catMenu?.let { c ->
        OptionsDialog(c, listOf(MenuOption("Masquer la catégorie") { scope.launch { vm.graph.library.setHidden(p.id, PrefKind.SERIES_CATEGORY, c, true) } }),
            onDismiss = { catMenu = null })
    }
}

@Composable
fun MovieDetailScreen(vm: MainViewModel, playlistId: Long, uid: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by vm.graph.library.changes.collectAsStateWithLifecycle()
    val history by vm.graph.library.observeHistory(300).collectAsStateWithLifecycle(emptyList())
    val movie by produceState<MovieRow?>(null, playlistId, uid, changes) { value = vm.graph.library.movieByUid(playlistId, uid) }
    val m = movie ?: return Loading()
    val info by produceState<XVodInfo?>(null, m.m.id) {
        val p = vm.graph.playlists.get(playlistId)
        if (p?.type == PlaylistType.XTREAM) value = runCatching { withContext(Dispatchers.IO) { vm.graph.playlists.xtream(p).vodInfo(m.m.streamId) } }.getOrNull()
    }
    val h: HistoryEntity? = history.firstOrNull { it.key == LibraryRepository.key(HistoryKind.MOVIE, playlistId, uid) }
    val focus = remember { FocusRequester() }
    val transfers by vm.graph.transfers.observe().collectAsStateWithLifecycle(emptyList())
    val dl = transfers.firstOrNull { it.kind == com.lumentv.data.db.TransferKind.DOWNLOAD && it.playlistId == playlistId && it.refUid == uid }

    val phone = com.lumentv.ui.LocalPhone.current
    // Phone: one scrolling column, poster on top; TV: poster on the left.
    val frame: @Composable (@Composable () -> Unit) -> Unit = { body ->
        if (phone) Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Poster(m.m.poster, m.m.name, Modifier.align(Alignment.CenterHorizontally).width(170.dp).aspectRatio(2f / 3f).padding(bottom = 12.dp))
            body()
            Spacer(Modifier.height(24.dp))
        } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Poster(m.m.poster, m.m.name, Modifier.width(220.dp).aspectRatio(2f / 3f))
            Column(Modifier.weight(1f)) { body() }
        }
    }
    frame {
        run {
            Text(m.m.name, style = MaterialTheme.typography.headlineMedium, color = Palette.Text, maxLines = 2)
            val meta = listOfNotNull(m.m.year ?: info?.releaseDate?.take(4), info?.durationSecs?.takeIf { it > 0 }?.let { duration(it) },
                m.m.rating?.let { "★ $it" }, info?.genre, m.m.category).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(vertical = 8.dp))
            ButtonRow(Modifier.padding(vertical = 12.dp)) {
                if (h != null && h.positionMs > 30_000 && (h.durationMs == 0L || h.positionMs < h.durationMs * 0.95)) {
                    TvButton("Reprendre à ${clock(h.positionMs)}", { PlayerLauncher.movie(context, playlistId, uid, true) }, Modifier.focusRequester(focus), primary = true)
                    TvButton("Depuis le début", { PlayerLauncher.movie(context, playlistId, uid, false) })
                } else {
                    TvButton("Lecture", { PlayerLauncher.movie(context, playlistId, uid, false) }, Modifier.focusRequester(focus), primary = true)
                }
                TvButton(if (m.isFavorite) "Retirer des favoris" else "Ajouter aux favoris", {
                    scope.launch { vm.graph.library.toggleFavorite(playlistId, PrefKind.MOVIE, uid) }
                })
                DownloadButton(vm, dl) {
                    scope.launch {
                        if (dl != null) vm.graph.transfers.retry(dl.id) else vm.graph.transfers.downloadMovie(m.m)
                        vm.show("Téléchargement lancé · suivez-le dans Téléchargés")
                    }
                }
            }
            Text(info?.plot ?: "Aucun résumé disponible.", style = MaterialTheme.typography.bodyLarge, color = Palette.Text, maxLines = if (phone) 40 else 8, overflow = TextOverflow.Ellipsis)
            info?.director?.takeIf { it.isNotBlank() }?.let { Text("Réalisation : $it", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(top = 10.dp)) }
            info?.cast?.takeIf { it.isNotBlank() }?.let { Text("Avec : $it", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 2) }
        }
    }
    LaunchedEffect(m.m.id) { runCatching { focus.requestFocus() } }
}

@Composable
fun SeriesDetailScreen(vm: MainViewModel, playlistId: Long, uid: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by vm.graph.library.changes.collectAsStateWithLifecycle()
    val history by vm.graph.library.observeHistory(500).collectAsStateWithLifecycle(emptyList())
    val series by produceState<SeriesRow?>(null, playlistId, uid, changes) { value = vm.graph.library.seriesByUid(playlistId, uid) }
    var error by remember { mutableStateOf<String?>(null) }
    val episodes by produceState<List<EpisodeEntity>?>(null, playlistId, uid) {
        val p = vm.graph.playlists.get(playlistId)
        val s = vm.graph.library.seriesByUid(playlistId, uid)
        value = if (p?.type == PlaylistType.XTREAM && s != null) {
            try { vm.graph.playlists.loadXtreamEpisodes(p, uid, s.s.seriesId) }
            catch (t: Throwable) { error = userMessage(t); vm.graph.library.storedEpisodes(playlistId, uid) }
        } else vm.graph.library.storedEpisodes(playlistId, uid)
    }
    val s = series ?: return Loading()
    val eps = episodes
    val seasons = eps?.map { it.season }?.distinct()?.sorted().orEmpty()
    var season by remember(seasons) { mutableStateOf(seasons.firstOrNull() ?: 1) }
    val progress = remember(history) { history.filter { it.kind == HistoryKind.EPISODE && it.parentUid == uid }.associateBy { it.uid } }
    val lastWatched = progress.values.maxByOrNull { it.watchedAt }
    // Watched = played to the end (same rule as the PC); "Marquer comme vu" writes such an entry, "non vu" removes it.
    fun watched(e: EpisodeEntity) = progress[e.uid]?.let { com.lumentv.core.Resume.watched(it.positionMs, it.durationMs) } == true
    fun markWatched(list: List<EpisodeEntity>, on: Boolean) = scope.launch {
        val now = System.currentTimeMillis()
        list.forEachIndexed { i, e ->
            val key = LibraryRepository.key(HistoryKind.EPISODE, playlistId, e.uid)
            if (!on) vm.graph.library.deleteHistory(key)
            else {
                val dur = (e.durationSecs * 1000L).coerceAtLeast(1)
                vm.graph.library.record(HistoryEntity(key, HistoryKind.EPISODE, playlistId, e.uid, series?.s?.name ?: e.title,
                    "S${e.season} E${e.episode} · ${e.title}", e.image ?: series?.s?.cover, uid, now - (list.size - i), dur, dur))
            }
        }
        vm.show(if (on) (if (list.size > 1) "${list.size} épisodes marqués comme vus" else "Marqué comme vu") else "Marqué comme non vu")
    }
    var epMenu by remember { mutableStateOf<EpisodeEntity?>(null) }
    val transfers by vm.graph.transfers.observe().collectAsStateWithLifecycle(emptyList())
    epMenu?.let { e ->
        val dl = transfers.firstOrNull { it.kind == com.lumentv.data.db.TransferKind.DOWNLOAD && it.playlistId == playlistId && it.refUid == e.uid }
        OptionsDialog(e.title, buildList {
            add(MenuOption("Lire") { PlayerLauncher.episode(context, playlistId, uid, e.uid, false) })
            if (watched(e)) add(MenuOption("Marquer comme non vu") { markWatched(listOf(e), false) })
            else add(MenuOption("Marquer comme vu") { markWatched(listOf(e), true) })
            if (dl?.status == com.lumentv.data.db.TransferStatus.DONE) add(MenuOption("Lire le fichier téléchargé") { PlayerLauncher.file(context, dl.id) })
            else if (dl == null || dl.status == com.lumentv.data.db.TransferStatus.FAILED) add(MenuOption("Télécharger l'épisode") {
                scope.launch { vm.graph.transfers.downloadEpisode(e, series?.s?.name ?: e.title); vm.show("Téléchargement lancé") } })
        }, onDismiss = { epMenu = null }, subtitle = dl?.let { "Téléchargement : " + it.status })
    }
    val focus = remember { FocusRequester() }

    val phone = com.lumentv.ui.LocalPhone.current
    // The top of the page (poster, summary, buttons, seasons) and one episode line, shared by both layouts.
    val header: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(if (phone) 14.dp else 24.dp), modifier = if (phone) Modifier else Modifier.height(210.dp)) {
            Poster(s.s.cover, s.s.name, Modifier.width(if (phone) 96.dp else 140.dp).aspectRatio(2f / 3f))
            Column(Modifier.weight(1f)) {
                Text(s.s.name, style = if (phone) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(s.s.year, s.s.rating?.let { "★ $it" }, "${seasons.size} saison(s)", s.s.category).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(vertical = 6.dp))
                Text(s.s.plot ?: "", style = MaterialTheme.typography.bodyMedium, color = Palette.Text, maxLines = if (phone) 3 else 4, overflow = TextOverflow.Ellipsis)
                ButtonRow(Modifier.padding(top = 12.dp)) {
                    if (lastWatched != null) TvButton("Reprendre : ${lastWatched.title.take(40)}", {
                        PlayerLauncher.episode(context, playlistId, uid, lastWatched.uid, true)
                    }, Modifier.focusRequester(focus), primary = true)
                    TvButton(if (s.isFavorite) "Retirer des favoris" else "Ajouter aux favoris", {
                        scope.launch { vm.graph.library.toggleFavorite(playlistId, PrefKind.SERIES, uid) }
                    }, if (lastWatched == null) Modifier.focusRequester(focus) else Modifier)
                    eps?.filter { it.season == season }?.takeIf { it.isNotEmpty() }?.let { list ->
                        val all = list.all { watched(it) }
                        TvButton(if (all) "Saison $season : marquer non vue" else "Marquer la saison $season comme vue", { markWatched(list, !all) })
                    }
                }
            }
        }
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                items(seasons) { n ->
                    FocusSurface({ season = n }, selected = n == season, shape = RoundedCornerShape(20.dp)) {
                        Text("Saison $n", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        } else Spacer(Modifier.height(10.dp))
    }
    val episodeRow: @Composable (EpisodeEntity) -> Unit = { e ->
                    val h = progress[e.uid]
                    val dl = transfers.firstOrNull { it.playlistId == playlistId && it.refUid == e.uid }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusSurface({ PlayerLauncher.episode(context, playlistId, uid, e.uid, h != null && h.positionMs > 30_000) }, Modifier.weight(1f), focusedScale = 1.01f,
                        onLongClick = { epMenu = e }) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("E${e.episode}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(if (phone) 40.dp else 56.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                e.plot?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                if (h != null && h.durationMs > 0) ProgressLine(h.positionMs.toFloat() / h.durationMs, Modifier.fillMaxWidth(0.5f).padding(top = 4.dp))
                            }
                            if (e.durationSecs > 0 && !phone) Text(duration(e.durationSecs), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // Right of each episode: mark it watched / not watched…
                    val seen = watched(e)
                    FocusSurface({ markWatched(listOf(e), !seen) }, Modifier.width(if (phone) 54.dp else 76.dp), focusedScale = 1.05f) {
                        Text(if (seen) "✓ Vu" else "○ Vu", style = MaterialTheme.typography.titleMedium,
                            color = if (seen) androidx.compose.ui.graphics.Color(0xFF4CD787) else Palette.TextDim,
                            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    // …download it, follow the download, or play the file.
                    FocusSurface({
                        when (dl?.status) {
                            com.lumentv.data.db.TransferStatus.DONE -> PlayerLauncher.file(context, dl.id)
                            com.lumentv.data.db.TransferStatus.RUNNING, com.lumentv.data.db.TransferStatus.SCHEDULED -> vm.openRoot(Screen.Recordings)
                            else -> scope.launch {
                                if (dl != null) vm.graph.transfers.retry(dl.id) else vm.graph.transfers.downloadEpisode(e, series?.s?.name ?: e.title)
                                vm.show("Téléchargement de l'épisode ${e.episode} lancé · suivez-le dans Téléchargés")
                            }
                        }
                    }, Modifier.width(if (phone) 54.dp else 76.dp), focusedScale = 1.05f) {
                        Text(when (dl?.status) {
                            com.lumentv.data.db.TransferStatus.DONE -> "▶ ✓"
                            com.lumentv.data.db.TransferStatus.RUNNING -> if (dl.totalBytes > 0) "${dl.bytes * 100 / dl.totalBytes} %" else "…"
                            else -> "⬇"
                        }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    }
    }
    if (phone) {
        // Phone: the whole page scrolls as one, so the episodes are not squeezed under the header.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
            item { header() }
            when {
                eps == null -> item { Loading("Chargement des épisodes…") }
                eps.isEmpty() -> item { EmptyState("Aucun épisode", error ?: "Le fournisseur ne liste aucun épisode pour cette série.") }
                else -> items(eps.filter { it.season == season }, key = { it.id }) { e -> episodeRow(e) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    } else Column(Modifier.fillMaxSize()) {
        header()
        when {
            eps == null -> Loading("Chargement des épisodes…")
            eps.isEmpty() -> EmptyState("Aucun épisode", error ?: "Le fournisseur ne liste aucun épisode pour cette série.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(eps.filter { it.season == season }, key = { it.id }) { e -> episodeRow(e) }
            }
        }
    }
    LaunchedEffect(s.s.id) { runCatching { focus.requestFocus() } }
}

/** Download state of a movie: start it, follow it, or play the file. */
@Composable
private fun DownloadButton(vm: MainViewModel, dl: com.lumentv.data.db.TransferEntity?, onDownload: () -> Unit) {
    val context = LocalContext.current
    when (dl?.status) {
        com.lumentv.data.db.TransferStatus.DONE -> TvButton("▶ Fichier téléchargé", { PlayerLauncher.file(context, dl.id) })
        com.lumentv.data.db.TransferStatus.RUNNING, com.lumentv.data.db.TransferStatus.SCHEDULED ->
            TvButton("⬇ " + (if (dl.totalBytes > 0) "${dl.bytes * 100 / dl.totalBytes} %" else "en cours"), { vm.openRoot(Screen.Recordings) })
        com.lumentv.data.db.TransferStatus.FAILED, com.lumentv.data.db.TransferStatus.CANCELLED -> TvButton("⬇ Reprendre le téléchargement", onDownload)
        else -> TvButton("⬇ Télécharger le film", onDownload)
    }
}

fun duration(secs: Int): String = if (secs >= 3600) "${secs / 3600} h ${"%02d".format((secs % 3600) / 60)}" else "${secs / 60} min"
fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}


// ---- Reprendre la lecture -------------------------------------------------------------------------------------

/** One poster of the "Reprendre la lecture" row. [forget] are the history lines removed with it. */
private class ResumeItem(val key: String, val title: String, val line: String?, val image: String?, val progress: Float?,
                         val play: () -> Unit, val forget: List<String>)

/** Films started and not finished, the last watched first. */
@Composable
private fun resumeMovies(vm: MainViewModel, pid: Long, history: List<HistoryEntity>): List<ResumeItem> {
    val context = LocalContext.current
    return remember(history, pid) {
        history.filter { it.kind == HistoryKind.MOVIE && it.playlistId == pid && com.lumentv.core.Resume.movieToResume(it.positionMs, it.durationMs) }
            .take(com.lumentv.core.Resume.LIMIT).map { h ->
                ResumeItem(h.key, h.title, com.lumentv.core.Resume.remaining(h.positionMs, h.durationMs), h.image,
                    h.positionMs.toFloat() / h.durationMs.coerceAtLeast(1), { PlayerLauncher.movie(context, pid, h.uid, true) }, listOf(h.key))
            }
    }
}

/**
 * Series to go on with, the last watched first: the episode left halfway, or the next one when the last was seen to
 * the end. A series whose last episode was its final one is done and leaves the row.
 */
@Composable
private fun resumeSeries(vm: MainViewModel, pid: Long, history: List<HistoryEntity>): List<ResumeItem> {
    val context = LocalContext.current
    val items by produceState(emptyList<ResumeItem>(), history, pid) {
        value = history.filter { it.kind == HistoryKind.EPISODE && it.playlistId == pid && it.parentUid != null }
            .groupBy { it.parentUid!! }.values.map { eps -> eps.maxBy { it.watchedAt } to eps.map { it.key } }
            .sortedByDescending { it.first.watchedAt }
            .mapNotNull { (h, keys) ->
                val sUid = h.parentUid!!
                val cover = vm.graph.library.seriesByUid(pid, sUid)?.s?.cover ?: h.image
                if (!com.lumentv.core.Resume.watched(h.positionMs, h.durationMs)) {
                    ResumeItem(sUid, h.title, listOfNotNull(h.subtitle?.substringBefore(" · "), com.lumentv.core.Resume.remaining(h.positionMs, h.durationMs)).joinToString(" · "),
                        cover, if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else null,
                        { PlayerLauncher.episode(context, pid, sUid, h.uid, true) }, keys)
                } else {
                    val eps = vm.graph.library.storedEpisodes(pid, sUid)
                    val i = eps.indexOfFirst { it.uid == h.uid }
                    when {
                        // Episodes not on this device yet (watched elsewhere): the series page fetches them.
                        eps.isEmpty() || i < 0 -> ResumeItem(sUid, h.title, "Continuer", cover, null, { vm.open(Screen.SeriesDetail(pid, sUid)) }, keys)
                        i + 1 < eps.size -> eps[i + 1].let { n ->
                            ResumeItem(sUid, h.title, "Suivant : S${n.season} E${n.episode}", cover, null, { PlayerLauncher.episode(context, pid, sUid, n.uid, false) }, keys)
                        }
                        else -> null
                    }
                }
            }.take(com.lumentv.core.Resume.LIMIT)
    }
    return items
}

/** The row above the posters; a long press on one takes it out (and forgets where it was). */
@Composable
private fun ResumeRow(vm: MainViewModel, items: List<ResumeItem>, series: Boolean, below: String) {
    if (items.isEmpty()) return
    val scope = rememberCoroutineScope()
    val phone = com.lumentv.ui.LocalPhone.current
    var removing by remember { mutableStateOf<ResumeItem?>(null) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Reprendre la lecture", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Spacer(Modifier.width(12.dp))
            Text(com.lumentv.ui.components.hint("Appui long sur OK pour retirer", "Appui long pour retirer"), style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim, modifier = Modifier.padding(bottom = 3.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(gridGap()), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
            items(items, key = { it.key }) { r ->
                Box(Modifier.width(if (phone) 104.dp else 150.dp)) {
                    PosterTile(r.title, r.image, r.line, r.progress, false, onClick = r.play, onLong = { removing = r })
                }
            }
        }
        Text(below, style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.padding(top = 4.dp))
    }
    removing?.let { r ->
        com.lumentv.ui.components.ConfirmDialog("Retirer « ${r.title} » ?",
            if (series) "La série quitte « Reprendre la lecture ». Les épisodes ne seront plus marqués comme vus."
            else "Le film quitte « Reprendre la lecture » et repartira du début.", "Retirer",
            onConfirm = { scope.launch { r.forget.forEach { vm.graph.library.deleteHistory(it) } } }, onDismiss = { removing = null })
    }
}
