@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import com.lumentv.ui.glassPill
import androidx.compose.foundation.lazy.itemsIndexed
import com.lumentv.ui.components.remembered
import com.lumentv.ui.components.focusMemory
import com.lumentv.ui.ScreenBackdrop
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.onFocusChanged
import com.lumentv.ui.components.enterHere
import com.lumentv.ui.components.enterOn
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
import com.lumentv.ui.components.returnFocus
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RECENT = "\u0001recent"
private const val ALL = "\u0001all"
/** TV: every series in a grid (ALL is the home there). */
private const val RECENT2 = "\u0001every"

@Composable
private fun CategoryColumn(title: String, cats: List<CategoryCount>?, extra: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit,
                           onLong: (String) -> Unit) {
    // One button showing the category; the list opens in a window (the grid takes the whole width).
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
}

/** The category button above, the grid below (it fills what is left). */
@Composable
private fun VodLayout(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) { content() }
}

/** As many posters a row as fit: about 7 on the TV, 3 on an upright phone, 6 sideways. */
@Composable
private fun posterCells(): GridCells = if (com.lumentv.ui.LocalPhone.current) GridCells.Adaptive(104.dp) else GridCells.Adaptive(118.dp)
@Composable
private fun gridGap() = if (com.lumentv.ui.LocalPhone.current) 10.dp else 14.dp

@Composable
private fun PosterTile(title: String, image: String?, subtitle: String?, progress: Float?, favorite: Boolean, onClick: () -> Unit, onLong: () -> Unit,
                       modifier: Modifier = Modifier) {
    Column {
        FocusSurface(onClick, modifier.fillMaxWidth().aspectRatio(2f / 3f), onLongClick = onLong, focusedScale = 1.06f, shape = RoundedCornerShape(8.dp)) {
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
    var filter by androidx.compose.runtime.saveable.rememberSaveable(p.id, cat) { mutableStateOf("") }
    val movieGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    TopOnChange(movieGrid, cat)
    // Put forward above "Ajoutés récemment": new, best rated, more of the last film watched.
    val lastMovie = remember(history, p.id) { history.firstOrNull { it.kind == HistoryKind.MOVIE && it.playlistId == p.id }?.uid }
    // TMDB's lists and recommendations from what was watched (playlist-only rows without the key); again when a film is watched.
    val ratings by vm.graph.library.observeRatings().collectAsStateWithLifecycle(emptyList())
    val shelves = memoFlow(vm, "shelves:m:${p.id}", p.id, changes, lastMovie, ratings) { vm.graph.discover.movies(p.id, history, ratings) }
    val featured = cat == RECENT && filter.isBlank()
    val phone = com.lumentv.ui.LocalPhone.current
    // TV: a category's grid goes back to the home with Back.
    androidx.compose.runtime.DisposableEffect(phone, cat) {
        vm.backHook = if (cat != RECENT) ({ cat = RECENT; vm.movieCategory = RECENT; true }) else null
        onDispose { vm.backHook = null }
    }
    if (featured) {
        var picker by remember { mutableStateOf(false) }
        var forget by remember { mutableStateOf<ResumeItem?>(null) }
        val catRows = cats.orEmpty().filter { !com.lumentv.core.Featured.foreign("", it.category) }.take(10)
        val rows = buildList {
            if (resume.isNotEmpty()) add(HomeRow("resume", "Reprendre la lecture", resume.map { r -> resumeTile(vm, r, false) { forget = r } }))
            shelves.forEachIndexed { i, sh -> add(HomeRow("shelf$i", sh.title, sh.items.map { movieTile(vm, "f$i", it, progress[it.m.uid]?.takeIf { h -> h.durationMs > 0 && com.lumentv.core.Resume.movieToResume(h.positionMs, h.durationMs) }?.let { h -> h.positionMs.toFloat() / h.durationMs }) })) }
            catRows.forEach { c ->
                val items = memoState(vm, "cat:m:${p.id}:${c.category}", p.id, changes, c.category) { vm.graph.library.categoryMovies(p.id, c.category) }
                add(HomeRow("cat:" + c.category, c.category, items.map { movieTile(vm, "c:" + c.category, it, null) }))
            }
        }
        if (phone) PhoneVodHome(vm, "hero:m:${p.id}", rows, listOf(
            PhoneAction("Catégories  ▾") { picker = true }, PhoneAction("Tous les films") { cat = ALL; vm.movieCategory = ALL }))
        else VodHome(vm, "hero:m:${p.id}", rows) {
            HeroButton("Catégories  ▾") { picker = true }
            HeroButton("Tous les films") { cat = ALL; vm.movieCategory = ALL }
        }
        if (picker) OptionsDialog("Films", listOf(MenuOption("Tous les films") { cat = ALL; vm.movieCategory = ALL }) +
            cats.orEmpty().map { c -> MenuOption("${c.category}  ·  ${c.count}") { cat = c.category; vm.movieCategory = c.category } }, onDismiss = { picker = false })
        forget?.let { r ->
            com.lumentv.ui.components.ConfirmDialog("Retirer « ${r.title} » ?", "Le film quitte « Reprendre la lecture » et repartira du début.", "Retirer",
                onConfirm = { scope.launch { r.forget.forEach { vm.graph.library.deleteHistory(it) } } }, onDismiss = { forget = null })
        }
        return
    }

    VodLayout {
        CategoryColumn("Films", cats, listOf(RECENT to "Accueil des films", ALL to "Tous les films"), cat, { cat = it; vm.movieCategory = it }, { catMenu = it })
        Column(Modifier.fillMaxSize()) {
        FilterField(filter, if (cat == ALL || cat == RECENT) "Rechercher un film" else "Rechercher dans « $cat »") { filter = it }
        val list = movies?.let { all -> normalize(filter).let { f -> if (f.isEmpty()) all else all.filter { it.m.nameNorm.contains(f) } } }
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState("Aucun film", if (filter.isBlank()) "Cette catégorie est vide." else "Rien ne correspond à « $filter ».")
            else -> LazyVerticalGrid(posterCells(), state = movieGrid, horizontalArrangement = Arrangement.spacedBy(gridGap()), verticalArrangement = Arrangement.spacedBy(gridGap()),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 20.dp, start = 6.dp, end = 6.dp),
                modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.m.id }) { r ->
                    val h = progress[r.m.uid]?.takeIf { it.playlistId == p.id }
                    PosterTile(shortTitle(r.m.name), r.m.poster, posterLine(r.m.year, r.m.rating, r.m.name),
                        h?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else null }, r.isFavorite,
                        onClick = { vm.openFrom("m:" + r.m.uid, Screen.MovieDetail(p.id, r.m.uid)) },
                        onLong = { scope.launch { val f = vm.graph.library.toggleFavorite(p.id, PrefKind.MOVIE, r.m.uid); vm.show(if (f) "Ajouté aux favoris" else "Retiré des favoris") } },
                        modifier = Modifier.returnFocus(vm, "m:" + r.m.uid))
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
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    TopOnChange(grid, cat)
    var catMenu by remember { mutableStateOf<String?>(null) }
    val cats by produceState<List<CategoryCount>?>(null, p.id, changes, settings.showHidden) { value = vm.graph.library.seriesCategories(p.id, settings.showHidden) }
    val series by produceState<List<SeriesRow>?>(null, p.id, cat, changes) {
        value = null
        value = when (cat) { ALL, RECENT2 -> vm.graph.library.series(p.id, null); RECENT -> vm.graph.library.recentSeries(p.id); else -> vm.graph.library.series(p.id, cat) }
    }
    var filter by androidx.compose.runtime.saveable.rememberSaveable(p.id, cat) { mutableStateOf("") }
    // Put forward above "Toutes les séries": new or with new episodes, best rated, more of the last series watched.
    val lastSeries = remember(history, p.id) { history.firstOrNull { it.kind == HistoryKind.EPISODE && it.playlistId == p.id }?.parentUid }
    val ratings by vm.graph.library.observeRatings().collectAsStateWithLifecycle(emptyList())
    val shelves = memoFlow(vm, "shelves:s:${p.id}", p.id, changes, lastSeries, ratings) { vm.graph.discover.series(p.id, history, ratings) }
    val featured = cat == ALL && filter.isBlank()
    val phone = com.lumentv.ui.LocalPhone.current
    androidx.compose.runtime.DisposableEffect(phone, cat) {
        vm.backHook = if (cat != ALL) ({ cat = ALL; vm.seriesCategory = ALL; true }) else null
        onDispose { vm.backHook = null }
    }
    if (featured) {
        var picker by remember { mutableStateOf(false) }
        var forget by remember { mutableStateOf<ResumeItem?>(null) }
        val catRows = cats.orEmpty().filter { !com.lumentv.core.Featured.foreign("", it.category) }.take(10)
        val rows = buildList {
            if (resume.isNotEmpty()) add(HomeRow("resume", "Reprendre la lecture", resume.map { r -> resumeTile(vm, r, true) { forget = r } }))
            shelves.forEachIndexed { i, sh -> add(HomeRow("shelf$i", sh.title, sh.items.map { seriesTile(vm, "f$i", it) })) }
            catRows.forEach { c ->
                val items = memoState(vm, "cat:s:${p.id}:${c.category}", p.id, changes, c.category) { vm.graph.library.categorySeries(p.id, c.category) }
                add(HomeRow("cat:" + c.category, c.category, items.map { seriesTile(vm, "c:" + c.category, it) }))
            }
        }
        if (phone) PhoneVodHome(vm, "hero:s:${p.id}", rows, listOf(PhoneAction("Catégories  ▾") { picker = true },
            PhoneAction("Toutes les séries") { cat = RECENT2; vm.seriesCategory = RECENT2 }, PhoneAction("Ajoutées récemment") { cat = RECENT; vm.seriesCategory = RECENT }))
        else VodHome(vm, "hero:s:${p.id}", rows) {
            HeroButton("Catégories  ▾") { picker = true }
            HeroButton("Ajoutées récemment") { cat = RECENT; vm.seriesCategory = RECENT }
        }
        if (picker) OptionsDialog("Séries", listOf(MenuOption("Toutes les séries") { cat = RECENT2; vm.seriesCategory = RECENT2 }, MenuOption("Ajoutées récemment") { cat = RECENT; vm.seriesCategory = RECENT }) +
            cats.orEmpty().map { c -> MenuOption("${c.category}  ·  ${c.count}") { cat = c.category; vm.seriesCategory = c.category } }, onDismiss = { picker = false })
        forget?.let { r ->
            com.lumentv.ui.components.ConfirmDialog("Retirer « ${r.title} » ?", "La série quitte « Reprendre la lecture ». Les épisodes ne seront plus marqués comme vus.", "Retirer",
                onConfirm = { scope.launch { r.forget.forEach { vm.graph.library.deleteHistory(it) } } }, onDismiss = { forget = null })
        }
        return
    }
    VodLayout {
        CategoryColumn("Séries", cats, listOf(ALL to "Accueil des séries", RECENT2 to "Toutes les séries", RECENT to "Ajoutées récemment"),
            cat, { cat = it; vm.seriesCategory = it }, { catMenu = it })
        Column(Modifier.fillMaxSize()) {
        FilterField(filter, if (cat == ALL || cat == RECENT) "Rechercher une série" else "Rechercher dans « $cat »") { filter = it }
        val list = series?.let { all -> normalize(filter).let { f -> if (f.isEmpty()) all else all.filter { it.s.nameNorm.contains(f) } } }
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState("Aucune série", if (filter.isBlank()) "Cette catégorie est vide." else "Rien ne correspond à « $filter ».")
            else -> LazyVerticalGrid(posterCells(), state = grid, horizontalArrangement = Arrangement.spacedBy(gridGap()), verticalArrangement = Arrangement.spacedBy(gridGap()),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 20.dp, start = 6.dp, end = 6.dp),
                modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.s.id }) { r ->
                    PosterTile(shortTitle(r.s.name), r.s.cover, posterLine(r.s.year, r.s.rating, null), null, r.isFavorite,
                        onClick = { vm.openFrom("s:" + r.s.uid, Screen.SeriesDetail(p.id, r.s.uid)) },
                        onLong = { scope.launch { val f = vm.graph.library.toggleFavorite(p.id, PrefKind.SERIES, r.s.uid); vm.show(if (f) "Ajoutée aux favoris" else "Retirée des favoris") } },
                        modifier = Modifier.returnFocus(vm, "s:" + r.s.uid))
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
    if (com.lumentv.ui.LocalPhone.current) PhoneMovieDetail(vm, playlistId, uid) else MovieDetailTv(vm, playlistId, uid)
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
    // Watched = played to the end (same rule as the PC); "Marquer comme vu" writes such an entry, "non vu" removes it.
    fun watched(e: EpisodeEntity) = progress[e.uid]?.let { com.lumentv.core.Resume.watched(it.positionMs, it.durationMs) } == true
    fun markWatched(list: List<EpisodeEntity>, on: Boolean) = scope.launch {
        // Dated before every viewing of the series (or keeping its own date): a mark is not "the last one watched".
        val now = (progress.values.minOfOrNull { it.watchedAt } ?: System.currentTimeMillis()) - list.size
        list.forEachIndexed { i, e ->
            val key = LibraryRepository.key(HistoryKind.EPISODE, playlistId, e.uid)
            if (!on) vm.graph.library.deleteHistory(key)
            else {
                val dur = (e.durationSecs * 1000L).coerceAtLeast(1)
                vm.graph.library.record(HistoryEntity(key, HistoryKind.EPISODE, playlistId, e.uid, series?.s?.name ?: e.title,
                    "S${e.season} E${e.episode} · ${e.title}", e.image ?: series?.s?.cover, uid, progress[e.uid]?.watchedAt ?: (now - (list.size - i)), dur, dur))
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
    val phone = com.lumentv.ui.LocalPhone.current
    if (!phone) SeriesDetailTv(vm, playlistId, uid, s, eps, error, seasons, season, { season = it }, progress, ::watched,
        { l, on -> markWatched(l, on) }, { epMenu = it })
    else PhoneSeriesDetail(vm, playlistId, uid, s, eps, error, seasons, season, { season = it }, progress, ::watched,
        { l, on -> markWatched(l, on) }, { epMenu = it })
}

/** "Hope (2026) MULTI" → "Hope": the name without the year and the copy's tags (they go to [posterLine]). */
internal fun shortTitle(name: String): String = name.substringBefore(" (").substringBefore(" [").trim().ifBlank { name }

/** "2026 · ★ 7,2 · MULTI": year, rating, and for a film which copy it is. */
internal fun posterLine(year: String?, rating: String?, name: String?): String? =
    (listOfNotNull(com.lumentv.core.Featured.yearOf(year, name ?: "")?.toString(),
        com.lumentv.core.Featured.rating(rating)?.let { "★ " + "%.1f".format(java.util.Locale.FRANCE, it) }) +
        listOfNotNull(name?.let { versionLabel(it) }?.takeIf { it != "Version" && it.length <= 14 })).joinToString(" · ").ifEmpty { null }

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
    @Suppress("UNCHECKED_CAST")
    val items by produceState(vm.memo["resume:s:$pid"] as? List<ResumeItem> ?: emptyList(), history, pid) {
        value = history.filter { it.kind == HistoryKind.EPISODE && it.playlistId == pid && it.parentUid != null }
            .groupBy { it.parentUid!! }.values.map { eps -> furthest(eps) to eps.map { it.key } to eps.maxOf { it.watchedAt } }
            .sortedByDescending { it.second }.map { it.first }
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
            }.take(com.lumentv.core.Resume.LIMIT).also { vm.memo["resume:s:$pid"] = it }
    }
    return items
}

// ---- Featured rows ----------------------------------------------------------------------------------------------

/** Like [memoState] for rows that come in steps (a flow): each step shows as soon as it is ready. */
@Composable
private fun <T> memoFlow(vm: MainViewModel, key: String, vararg keys: Any?, load: () -> kotlinx.coroutines.flow.Flow<List<T>>): List<T> {
    @Suppress("UNCHECKED_CAST")
    val v by produceState(vm.memo[key] as? List<T> ?: emptyList(), *keys) {
        // Back on the screen, the rows already shown stay until the new ones are complete: a shorter first step would
        // take away the poster the viewer comes back to (and its focus).
        val shown = value.isNotEmpty()
        var latest: List<T>? = null
        runCatching { load().collect { rows -> latest = rows; if (!shown) { value = rows; vm.memo[key] = rows } } }
        latest?.let { value = it; vm.memo[key] = it }
    }
    return v
}

/** Computed in the background, and kept by the view model: back on the screen, the rows are there at once. */
@Composable
private fun <T> memoState(vm: MainViewModel, key: String, vararg keys: Any?, load: suspend () -> List<T>): List<T> {
    @Suppress("UNCHECKED_CAST")
    val v by produceState(vm.memo[key] as? List<T> ?: emptyList(), *keys) {
        value = runCatching { load() }.onFailure { android.util.Log.w("Featured", "$key: $it") }.getOrDefault(value)
            .also { vm.memo[key] = it }
    }
    return v
}

/**
 * Another category starts at its top. Back from a film page (same category) keeps the place: the category last shown
 * is remembered with the screen.
 */
@Composable
private fun TopOnChange(grid: androidx.compose.foundation.lazy.grid.LazyGridState, cat: String) {
    var shown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(cat) }
    LaunchedEffect(cat) { if (cat != shown) { shown = cat; grid.scrollToItem(0) } }
}

// ---- V2 home of Films and Séries (TV) ---------------------------------------------------------------------------

/** What the banner shows: the focused film or series. [movie]: its details (length, genre, backdrop) are asked for. */
internal class HeroItem(
    val key: String, val title: String, val meta: List<String>, val plot: String?, val image: String?,
    val movie: com.lumentv.data.db.MovieEntity? = null, val playLabel: String = "Lecture",
    val play: () -> Unit, val more: () -> Unit,
    /** For the title's logo (TMDB). */
    val series: Boolean = false, val tmdb: String? = null, val year: Int? = null,
    /** The provider's rating, shown when TMDB does not know the title. */
    val rating: String? = null,
)

internal class HomeTile(val key: String, val title: String, val image: String?, val progress: Float?, val hero: HeroItem,
                       val onClick: () -> Unit, val onLong: (() -> Unit)? = null)

internal class HomeRow(val key: String, val title: String, val tiles: List<HomeTile>)

/** "★ 7,3", "2025", "2 h 09", "Action, Aventure" */
internal fun metaOf(rating: String?, year: String?, vararg more: String?): List<String> =
    listOfNotNull(com.lumentv.core.Featured.rating(rating)?.let { "★ " + "%.1f".format(java.util.Locale.FRANCE, it) }, year?.take(4)) + more.filterNotNull().filter { it.isNotBlank() }

@Composable
private fun movieTile(vm: MainViewModel, rowKey: String, r: MovieRow, progress: Float?): HomeTile {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pid = r.m.playlistId
    return HomeTile("$rowKey:m:${r.m.uid}", r.m.name, r.m.poster, progress,
        HeroItem("m:${r.m.uid}", r.m.name.substringBefore(" (").trim(), metaOf(null, com.lumentv.core.Featured.yearOf(r.m.year, r.m.name)?.toString()), null, r.m.poster, rating = r.m.rating,
            movie = r.m, playLabel = if (progress != null) "Reprendre" else "Lecture", year = com.lumentv.core.Featured.yearOf(r.m.year, r.m.name),
            play = { PlayerLauncher.movie(context, pid, r.m.uid, progress != null) }, more = { vm.openFrom("$rowKey:m:${r.m.uid}", Screen.MovieDetail(pid, r.m.uid)) }),
        onClick = { vm.openFrom("$rowKey:m:${r.m.uid}", Screen.MovieDetail(pid, r.m.uid)) },
        onLong = { scope.launch { val f = vm.graph.library.toggleFavorite(pid, PrefKind.MOVIE, r.m.uid); vm.show(if (f) "Ajouté aux favoris" else "Retiré des favoris") } })
}

@Composable
private fun seriesTile(vm: MainViewModel, rowKey: String, r: SeriesRow): HomeTile {
    val scope = rememberCoroutineScope()
    val pid = r.s.playlistId
    val open = { vm.openFrom("$rowKey:s:${r.s.uid}", Screen.SeriesDetail(pid, r.s.uid)) }
    return HomeTile("$rowKey:s:${r.s.uid}", r.s.name, r.s.cover, null,
        HeroItem("s:${r.s.uid}", r.s.name, metaOf(null, r.s.year, r.s.category), r.s.plot, r.s.backdrop ?: r.s.cover, rating = r.s.rating,
            playLabel = "Voir la série", play = open, more = open, series = true, tmdb = r.s.tmdb, year = com.lumentv.core.Featured.year(r.s.year)),
        onClick = open,
        onLong = { scope.launch { val f = vm.graph.library.toggleFavorite(pid, PrefKind.SERIES, r.s.uid); vm.show(if (f) "Ajoutée aux favoris" else "Retirée des favoris") } })
}

@Composable
private fun resumeTile(vm: MainViewModel, r: ResumeItem, series: Boolean, onLong: () -> Unit): HomeTile =
    HomeTile("resume:${r.key}", r.title, r.image, r.progress,
        HeroItem("r:${r.key}", r.title, listOfNotNull(r.line), if (series) "Reprendre là où vous en étiez." else null, r.image,
            playLabel = "Reprendre", play = r.play, more = r.play, series = series),
        onClick = r.play, onLong = onLong)

/**
 * The home of Films or Séries on the TV, as on a streaming service: a banner with the focused title over its picture
 * (Lecture, Plus d'infos), then rows of posters. [actions]: the buttons after the banner's own (Catégories…).
 */
/**
 * Which poster has the focus, which row, and the title the banner shows. Read only by the banner and by the row
 * scroller, never by the home itself: moving from poster to poster then redraws the banner, not every row and poster
 * of the home (measured on the box: the UI thread was the bottleneck, a frame every 31 ms).
 */
@androidx.compose.runtime.Stable
private class HomeFocus(initial: HeroItem?) {
    var pending by mutableStateOf<HeroItem?>(null)
    var hero by mutableStateOf(initial)
    var row by androidx.compose.runtime.mutableIntStateOf(-1)
    var rowsFocused by mutableStateOf(false)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VodHome(vm: MainViewModel, memoKey: String, rows: List<HomeRow>, actions: @Composable () -> Unit) {
    val first = rows.firstOrNull { it.tiles.isNotEmpty() }?.tiles?.firstOrNull()?.hero
    val focus = remember { HomeFocus(vm.memo[memoKey] as? HeroItem) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        HeroArea(vm, memoKey, focus, first, actions)
        RowScroller(focus, list)
        // Rows arriving late (resume, featured) go above the categories: the list then shows the top, unless it is in use.
        var inUse by remember { mutableStateOf(false) }
        val shown = rows.filter { it.tiles.isNotEmpty() }
        // Only when a row really comes first (not on return from a film: the list then keeps its place and the poster its focus).
        var firstKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(shown.firstOrNull()?.key) {
            val k = shown.firstOrNull()?.key
            if (k != firstKey) { if (firstKey != null && !inUse && vm.returnKey == null) list.scrollToItem(0); firstKey = k }
        }
        // The list's place is set by RowScroller only (row title at the top): a poster getting the focus (and growing)
        // must not nudge the list down. The rows themselves keep the usual sideways scrolling (rowSpec below).
        val rowSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides StayPut) {
        LazyColumn(Modifier.fillMaxSize().onFocusChanged { inUse = it.hasFocus; focus.rowsFocused = it.hasFocus }, state = list, verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 30.dp)) {
            shown.forEachIndexed { rowIndex, row ->
                item(key = row.key) {
                    Column {
                        Text(row.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                        // Entering a row (Up / Down) lands on its first poster, or the one it was left from, not the one level with the focus.
                        val mem = com.lumentv.ui.components.rememberFocusMemory()
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowSpec) {
                        LazyRow(Modifier.focusMemory(mem), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                            itemsIndexed(row.tiles, key = { _, t -> t.key }) { i, t ->
                                HomePoster(vm, t, Modifier.remembered(mem, first = i == 0)) { focus.pending = t.hero; focus.row = rowIndex }
                            }
                        }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Never scrolls to bring a focused child into view (the home's list of rows, see VodHome). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object StayPut : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/** The banner and the picture behind: they follow the focused poster, a moment after it stops moving (no flicker). */
@Composable
private fun HeroArea(vm: MainViewModel, memoKey: String, focus: HomeFocus, first: HeroItem?, actions: @Composable () -> Unit) {
    LaunchedEffect(focus.pending) { focus.pending?.let { delay(280); focus.hero = it; vm.memo[memoKey] = it } }
    val hero = focus.hero ?: first
    val info by produceState<XVodInfo?>(hero?.movie?.let { vm.graph.artwork.cached(it) }, hero?.key) {
        value = hero?.movie?.let { vm.graph.artwork.movie(it) }
    }
    ScreenBackdrop(info?.backdrop ?: hero?.image)
    // The banner keeps its size (the rows slide under it, see RowScroller).
    if (hero != null) Hero(vm, hero, info, actions)
}

/** The row going into focus slides to the top of the list, so its title ("Tendances du moment"…) stays on screen. */
@Composable
private fun RowScroller(focus: HomeFocus, list: androidx.compose.foundation.lazy.LazyListState) {
    LaunchedEffect(focus.row, focus.rowsFocused) { if (focus.rowsFocused && focus.row >= 0) list.animateScrollToItem(focus.row) }
}

@Composable
private fun Hero(vm: MainViewModel, h: HeroItem, info: XVodInfo?, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().height(232.dp).padding(start = 4.dp, top = 8.dp), verticalArrangement = Arrangement.Bottom) {
        val t = tmdbInfo(vm, h.title, h.series, h.tmdb ?: info?.tmdb, h.year)
        TitleArt(h.title, t?.logo, 30.sp, 84.dp)
        val meta = listOfNotNull(ratingText(t, h.rating ?: info?.rating)) + h.meta + listOfNotNull(info?.durationSecs?.takeIf { it > 0 }?.let { duration(it) }, info?.genre?.split(',')?.take(2)?.joinToString(" • ") { it.trim() })
        if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Palette.Text.copy(alpha = 0.85f),
            maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        (t?.overview ?: info?.plot ?: h.plot)?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Text.copy(alpha = 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.52f).padding(top = 6.dp))
        }
        // Down from the tabs lands on "Lecture", not on the button under the tab.
        val buttons = com.lumentv.ui.components.rememberFocusMemory()
        Row(Modifier.padding(top = 14.dp).focusMemory(buttons), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            HeroButton("▶  " + h.playLabel, primary = true, modifier = Modifier.remembered(buttons, first = true), onClick = h.play)
            HeroButton("Plus d'infos", onClick = h.more)
            actions()
        }
    }
}

/** A banner button: white when it is the main one, glassy otherwise. */
@Composable
fun HeroButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick, modifier, shape = RoundedCornerShape(24.dp), container = if (primary) Color(0xE6F2F2F2) else Color(0x66262629), focusedScale = 1.06f) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (primary) Palette.OnFocus else androidx.tv.material3.LocalContentColor.current,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
    }
}

@Composable
private fun HomePoster(vm: MainViewModel, t: HomeTile, modifier: Modifier = Modifier, onFocus: () -> Unit) {
    FocusSurface(t.onClick, modifier.width(104.dp).aspectRatio(2f / 3f).returnFocus(vm, t.key).onFocusChanged { if (it.isFocused) onFocus() },
        onLongClick = t.onLong, focusedScale = 1.08f, shape = RoundedCornerShape(10.dp)) {
        Poster(t.image, t.title, Modifier.fillMaxSize())
        t.progress?.takeIf { it > 0f }?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp)) }
    }
}

// ---- V2 pages of a film and a series (TV) -----------------------------------------------------------------------

/** "Breach (2026) VOSTFR" → "VOSTFR", "The Dog Stars (2026) 4K MULTI" → "4K MULTI": what tells a copy from another. */
internal fun versionLabel(name: String): String =
    name.substringAfterLast(")", "").replace(Regex("^[\\s\\-|:]+"), "").trim().ifBlank { name.substringAfter(" | ", "").trim() }.ifBlank { "Version" }

/** A film's page on the TV: its picture behind, title, details, the buttons, then the summary and the cast. */
@Composable
private fun MovieDetailTv(vm: MainViewModel, playlistId: Long, startUid: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The copy shown: "Version" switches to another one of the same film (4K, VF, VOSTFR…).
    var uid by androidx.compose.runtime.saveable.rememberSaveable(startUid) { mutableStateOf(startUid) }
    val changes by vm.graph.library.changes.collectAsStateWithLifecycle()
    val history by vm.graph.library.observeHistory(300).collectAsStateWithLifecycle(emptyList())
    val movie by produceState<MovieRow?>(null, playlistId, uid, changes) { value = vm.graph.library.movieByUid(playlistId, uid) }
    val m = movie ?: return Loading()
    val info by produceState(vm.graph.artwork.cached(m.m), m.m.id) { value = vm.graph.artwork.movie(m.m) }
    val versions by produceState(emptyList<MovieRow>(), m.m.id) { value = vm.graph.library.versions(m.m) }
    var versionMenu by remember { mutableStateOf(false) }
    val h: HistoryEntity? = history.firstOrNull { it.key == LibraryRepository.key(HistoryKind.MOVIE, playlistId, uid) }
    val resumable = h != null && h.positionMs > 30_000 && (h.durationMs == 0L || h.positionMs < h.durationMs * 0.95)
    val transfers by vm.graph.transfers.observe().collectAsStateWithLifecycle(emptyList())
    val dl = transfers.firstOrNull { it.kind == com.lumentv.data.db.TransferKind.DOWNLOAD && it.playlistId == playlistId && it.refUid == uid }
    val focus = remember { FocusRequester() }
    ScreenBackdrop(info?.backdrop ?: m.m.poster)
    val ratings by vm.graph.library.observeRatings().collectAsStateWithLifecycle(emptyList())
    val myStars = ratings.firstOrNull { it.key == LibraryRepository.key(HistoryKind.MOVIE, playlistId, uid) }?.stars ?: 0
    val buttons = com.lumentv.ui.components.rememberFocusMemory()

    // No scrolling: the page fits the screen, so moving between the buttons and the stars never shifts it.
    Column(Modifier.fillMaxSize().padding(start = 4.dp, top = 28.dp)) {
        val t = tmdbInfo(vm, m.m.name, false, info?.tmdb, com.lumentv.core.Featured.yearOf(m.m.year, m.m.name))
        TitleArt(m.m.name, t?.logo, 36.sp, 110.dp)
        val meta = listOfNotNull(ratingText(t, m.m.rating)) + metaOf(null, com.lumentv.core.Featured.yearOf(m.m.year ?: info?.releaseDate, m.m.name)?.toString(),
            info?.durationSecs?.takeIf { it > 0 }?.let { duration(it) }, info?.genre?.split(',')?.take(3)?.joinToString(" • ") { it.trim() })
        Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = Palette.Text.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        Text(t?.overview ?: info?.plot ?: "Aucun résumé disponible.", style = MaterialTheme.typography.bodyLarge, color = Palette.Text.copy(alpha = 0.92f), maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.55f).padding(top = 10.dp))
        if (resumable) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressLine(h!!.positionMs.toFloat() / h.durationMs.coerceAtLeast(1), Modifier.width(220.dp))
            Text("  " + (com.lumentv.core.Resume.remaining(h.positionMs, h.durationMs) ?: ""), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        Row(Modifier.padding(top = 18.dp).focusMemory(buttons), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (resumable) {
                HeroButton("▶  Reprendre à ${clock(h!!.positionMs)}", primary = true, modifier = Modifier.focusRequester(focus).remembered(buttons, first = true)) { PlayerLauncher.movie(context, playlistId, uid, true) }
                HeroButton("Depuis le début", modifier = Modifier.remembered(buttons)) { PlayerLauncher.movie(context, playlistId, uid, false) }
            } else HeroButton("▶  Lecture", primary = true, modifier = Modifier.focusRequester(focus).remembered(buttons, first = true)) { PlayerLauncher.movie(context, playlistId, uid, false) }
            if (versions.size > 1) HeroButton("Version : ${versionLabel(m.m.name)}  ▾", modifier = Modifier.remembered(buttons)) { versionMenu = true }
            HeroButton(if (m.isFavorite) "★  Dans ma liste" else "+  Ma liste", modifier = Modifier.remembered(buttons)) { scope.launch { vm.graph.library.toggleFavorite(playlistId, PrefKind.MOVIE, uid) } }
            HeroButton(modifier = Modifier.remembered(buttons), text = when (dl?.status) {
                com.lumentv.data.db.TransferStatus.DONE -> "⬇  Téléchargé"
                com.lumentv.data.db.TransferStatus.RUNNING, com.lumentv.data.db.TransferStatus.SCHEDULED -> "⬇  En cours…"
                else -> "⬇  Télécharger"
            }) {
                when (dl?.status) {
                    com.lumentv.data.db.TransferStatus.DONE -> PlayerLauncher.file(context, dl.id)
                    com.lumentv.data.db.TransferStatus.RUNNING, com.lumentv.data.db.TransferStatus.SCHEDULED -> vm.openRoot(Screen.Recordings)
                    else -> scope.launch {
                        if (dl != null) vm.graph.transfers.retry(dl.id) else vm.graph.transfers.downloadMovie(m.m)
                        vm.show("Téléchargement lancé · suivez-le dans Téléchargés")
                    }
                }
            }
        }
        // A film started or watched can be rated (and the rating changed at any time).
        if (h != null || myStars > 0) com.lumentv.ui.components.StarRating(myStars, if (myStars > 0) "Votre note" else "Notez ce film",
            { n -> scope.launch { vm.graph.library.rate(HistoryKind.MOVIE, playlistId, uid, n, m.m.name.substringBefore(" (").trim(), m.m.poster)
                vm.show(if (n == 0) "Note retirée" else "Note enregistrée : $n/5") } }, Modifier.padding(top = 16.dp))
        t?.makers?.takeIf { it.isNotEmpty() }?.let { Text("Réalisation : " + it.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(top = 14.dp)) }
        t?.cast?.takeIf { it.isNotEmpty() }?.let { Text("Avec : " + it.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.6f).padding(top = 4.dp)) }
        Text(m.m.category, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
    }
    if (versionMenu) OptionsDialog("Version", versions.map { v ->
        MenuOption(versionLabel(v.m.name) + (com.lumentv.core.Featured.rating(v.m.rating)?.let { "  ·  ★ $it" } ?: ""), checked = v.m.uid == uid) { uid = v.m.uid }
    }, onDismiss = { versionMenu = false }, subtitle = "La même œuvre en plusieurs qualités ou langues")
    LaunchedEffect(m.m.id) { delay(80); runCatching { focus.requestFocus() } }
}

/** A series' page on the TV: picture behind, details, buttons, the seasons, then its episodes as wide cards. */
@Composable
private fun SeriesDetailTv(
    vm: MainViewModel, playlistId: Long, uid: String, s: SeriesRow, eps: List<EpisodeEntity>?, error: String?, seasons: List<Int>, season: Int,
    onSeason: (Int) -> Unit, progress: Map<String, HistoryEntity>, watched: (EpisodeEntity) -> Boolean, markWatched: (List<EpisodeEntity>, Boolean) -> Unit,
    onMenu: (EpisodeEntity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    ScreenBackdrop(s.s.backdrop ?: s.s.cover)
    val ratings by vm.graph.library.observeRatings().collectAsStateWithLifecycle(emptyList())
    val myStars = ratings.firstOrNull { it.key == LibraryRepository.key("se", playlistId, uid) }?.stars ?: 0
    val buttons = com.lumentv.ui.components.rememberFocusMemory()
    val seasonMemory = com.lumentv.ui.components.rememberFocusMemory()
    val episodeMemory = com.lumentv.ui.components.rememberFocusMemory()
    val last = progress.values.maxByOrNull { it.watchedAt }
    // The episode "Lecture" starts: the furthest one reached (by season and number): itself when left halfway, else the
    // one after it; a series seen to its last episode starts again at the first.
    val next = nextEpisode(eps, progress, watched)
    var low by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(start = 4.dp, top = 12.dp)) {
        val t = tmdbInfo(vm, s.s.name, true, s.s.tmdb, com.lumentv.core.Featured.year(s.s.year))
        TitleArt(s.s.name, t?.logo, if (low) 24.sp else 34.sp, if (low) 52.dp else 92.dp)
        Text((listOfNotNull(ratingText(t, s.s.rating)) + metaOf(null, s.s.year, if (seasons.isNotEmpty()) "${seasons.size} saison" + if (seasons.size > 1) "s" else "" else null, s.s.category)).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyLarge, color = Palette.Text.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        // One line, read whole: who created it, then the first four actors (the page has no room for a second line).
        if (!low) t?.let { i -> listOfNotNull(i.makers.takeIf { it.isNotEmpty() }?.let { "Créée par " + it.joinToString(", ") },
            i.cast.takeIf { it.isNotEmpty() }?.let { "Avec " + it.take(4).joinToString(", ") }).joinToString("   ·   ").takeIf { it.isNotEmpty() } }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.95f).padding(top = 6.dp))
        }
        if (!low) (t?.overview ?: s.s.plot)?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.Text.copy(alpha = 0.92f), maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.55f).padding(top = 8.dp)) }
        Row(Modifier.padding(top = 12.dp).focusMemory(buttons), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            next?.let { (e, resume) ->
                HeroButton("▶  " + (if (resume) "Reprendre" else "Lecture") + " S${e.season} E${e.episode}", primary = true,
                    modifier = Modifier.focusRequester(focus).remembered(buttons, first = true)) {
                    PlayerLauncher.episode(context, playlistId, uid, e.uid, resume)
                }
            }
            HeroButton(if (s.isFavorite) "★  Dans ma liste" else "+  Ma liste",
                modifier = (if (next == null) Modifier.focusRequester(focus) else Modifier).remembered(buttons, first = next == null)) {
                scope.launch { vm.graph.library.toggleFavorite(playlistId, PrefKind.SERIES, uid) }
            }
            eps?.filter { it.season == season }?.takeIf { it.isNotEmpty() }?.let { list ->
                val all = list.all { watched(it) }
                HeroButton(if (all) "Saison $season : non vue" else "Saison $season vue", modifier = Modifier.remembered(buttons)) { markWatched(list, !all) }
            }
        }
        // A series of which an episode was watched can be rated.
        if (!low && (progress.isNotEmpty() || myStars > 0)) com.lumentv.ui.components.StarRating(myStars, if (myStars > 0) "Votre note" else "Notez cette série",
            { n -> scope.launch { vm.graph.library.rate("se", playlistId, uid, n, s.s.name, s.s.cover); vm.show(if (n == 0) "Note retirée" else "Note enregistrée : $n/5") } },
            Modifier.padding(top = 8.dp))
        Column(Modifier.weight(1f).onFocusChanged { low = it.hasFocus }) {
        if (seasons.size > 1) Row(Modifier.padding(top = 10.dp).glassPill().padding(4.dp)) {
            LazyRow(Modifier.focusMemory(seasonMemory), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(seasons) { n ->
                    com.lumentv.ui.GlassChip({ onSeason(n) }, Modifier.remembered(seasonMemory, first = n == season).height(38.dp), selected = n == season) {
                        Text("Saison $n", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp))
                    }
                }
            }
        }
        when {
            eps == null -> Loading("Chargement des épisodes…")
            eps.isEmpty() -> EmptyState("Aucun épisode", error ?: "Le fournisseur ne liste aucun épisode pour cette série.")
            else -> LazyRow(Modifier.padding(top = 10.dp).focusMemory(episodeMemory), horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                items(eps.filter { it.season == season }, key = { it.id }) { e ->
                    val h = progress[e.uid]
                    Column(Modifier.width(184.dp)) {
                        FocusSurface({ PlayerLauncher.episode(context, playlistId, uid, e.uid, h != null && h.positionMs > 30_000 && !watched(e)) },
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f).remembered(episodeMemory, first = e === eps.firstOrNull { it.season == season }), onLongClick = { onMenu(e) }, focusedScale = 1.06f, shape = RoundedCornerShape(10.dp)) {
                            Poster(e.image ?: s.s.backdrop ?: s.s.cover, e.title, Modifier.fillMaxSize())
                            if (watched(e)) Text("✓ Vu", style = MaterialTheme.typography.labelMedium, color = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp))
                            if (h != null && h.durationMs > 0 && !watched(e)) ProgressLine(h.positionMs.toFloat() / h.durationMs, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
                        }
                        Text("${e.episode}. " + e.title.substringAfterLast(" - ").ifBlank { e.title }, style = MaterialTheme.typography.labelLarge, color = Palette.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        if (e.durationSecs > 0) Text(duration(e.durationSecs), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    }
                }
            }
        }
        Text(com.lumentv.ui.components.hint("Maintenez OK sur un épisode : vu / non vu, télécharger", ""), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
            modifier = Modifier.padding(top = 2.dp))
        }
    }
    LaunchedEffect(s.s.id, next != null) { delay(80); runCatching { focus.requestFocus() } }
}

/** What TMDB says of a title (logo, rating), asked in the background and kept for the session. */
@Composable
internal fun tmdbInfo(vm: MainViewModel, title: String, series: Boolean, tmdb: String?, year: Int?): com.lumentv.data.TitleLogos.Info? {
    val clean = title.substringBefore(" (").substringBefore(" [").trim()
    val info by produceState(vm.graph.logos.cached(series, tmdb, clean, year), series, tmdb, clean, year) {
        value = withContext(Dispatchers.IO) { vm.graph.logos.info(series, tmdb, clean, year) }
    }
    return info
}

/** "★ 7,3" from TMDB when it knows the title, else the provider's rating. */
internal fun ratingText(tmdb: com.lumentv.data.TitleLogos.Info?, provider: String?): String? =
    (tmdb?.rating ?: com.lumentv.core.Featured.rating(provider))?.let { "★ " + "%.1f".format(java.util.Locale.FRANCE, it) }

/**
 * The title as its poster writes it (the logo, from TMDB) when there is one, else in big letters. The text shows
 * while the logo is looked for, so nothing jumps when there is none.
 */
@Composable
internal fun TitleArt(title: String, logo: String?, size: androidx.compose.ui.unit.TextUnit, logoHeight: androidx.compose.ui.unit.Dp) {
    val clean = title.substringBefore(" (").substringBefore(" [").trim()
    androidx.compose.animation.Crossfade(logo, label = "title") { url ->
        if (url != null) coil.compose.AsyncImage(url, clean, contentScale = androidx.compose.ui.layout.ContentScale.Fit, alignment = Alignment.BottomStart,
            modifier = Modifier.height(logoHeight).fillMaxWidth(0.42f))
        else Text(clean, fontSize = size, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = size * 1.12f, modifier = Modifier.fillMaxWidth(0.6f))
    }
}

/** "S4 E6 · …" → (4, 6). */
private fun seasonEpisode(subtitle: String?): Pair<Int, Int>? =
    subtitle?.let { Regex("^S(\\d+) E(\\d+)").find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

/**
 * Of a series' history, the episode furthest in the series (season, then number), the last one watched when unknown:
 * the series is resumed there, and leaves "Reprendre la lecture" once its last episode is seen.
 */
private fun furthest(eps: List<HistoryEntity>): HistoryEntity =
    eps.filter { seasonEpisode(it.subtitle) != null }.maxWithOrNull(compareBy({ seasonEpisode(it.subtitle)!!.first }, { seasonEpisode(it.subtitle)!!.second }))
        ?: eps.maxBy { it.watchedAt }
