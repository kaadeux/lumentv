@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

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
    if (true) {
        // One button showing the category; the list opens in a window (V2: on the TV too, the grid takes the width).
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
        // Back from the posters, Left lands on the category chosen, not on the one level with the poster.
        val chosen = com.lumentv.ui.components.rememberEnterTarget()
        LazyColumn(Modifier.enterOn(chosen), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(extra, key = { it.first }) { (id, label) ->
                FocusSurface({ onSelect(id) }, Modifier.fillMaxWidth().then(if (id == selected) Modifier.enterHere(chosen) else Modifier), selected = id == selected, focusedScale = 1.02f) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
            items(cats.orEmpty(), key = { "c:" + it.category }) { c ->
                FocusSurface({ onSelect(c.category) }, Modifier.fillMaxWidth().then(if (c.category == selected) Modifier.enterHere(chosen) else Modifier),
                    onLongClick = { onLong(c.category) }, selected = c.category == selected, focusedScale = 1.02f) {
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
    Column(Modifier.fillMaxSize()) { content() }
}

/** 5 posters a row on the TV; on a phone as many as fit, about 3 upright and 6 sideways. */
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
    val shelves = memoState(vm, "shelves:m:${p.id}", p.id, changes, lastMovie) { vm.graph.discover.movies(p.id, history) }
    val featured = cat == RECENT && filter.isBlank()
    KeepTop(movieGrid, if (featured) headerCount(resume.isNotEmpty(), shelves.size) else 0)
    val phone = com.lumentv.ui.LocalPhone.current
    // TV: a category's grid goes back to the home with Back.
    androidx.compose.runtime.DisposableEffect(phone, cat) {
        vm.backHook = if (!phone && cat != RECENT) ({ cat = RECENT; vm.movieCategory = RECENT; true }) else null
        onDispose { vm.backHook = null }
    }
    if (!phone && featured) {
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
        VodHome(vm, "hero:m:${p.id}", rows) {
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
        CategoryColumn("Films", cats, listOf(RECENT to (if (phone) "Ajoutés récemment" else "Accueil des films"), ALL to "Tous les films"), cat, { cat = it; vm.movieCategory = it }, { catMenu = it })
        Column(Modifier.fillMaxSize()) {
        FilterField(filter, if (cat == ALL || cat == RECENT) "Rechercher un film" else "Rechercher dans « $cat »") { filter = it }
        val list = movies?.let { all -> normalize(filter).let { f -> if (f.isEmpty()) all else all.filter { it.m.nameNorm.contains(f) } } }
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState("Aucun film", if (filter.isBlank()) "Cette catégorie est vide." else "Rien ne correspond à « $filter ».")
            else -> LazyVerticalGrid(posterCells(), state = movieGrid, horizontalArrangement = Arrangement.spacedBy(gridGap()), verticalArrangement = Arrangement.spacedBy(gridGap()),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 20.dp, start = 6.dp, end = 6.dp),
                modifier = Modifier.fillMaxSize()) {
                if (featured) {
                    if (resume.isNotEmpty()) item(key = "resume", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { ResumeRow(vm, resume, false) }
                    shelves.forEachIndexed { i, sh ->
                        item(key = "shelf$i", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            ShelfRow(sh.title, sh.items, { it.m.uid }) { r ->
                                PosterTile(r.m.name, r.m.poster, listOfNotNull(r.m.year, r.m.rating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null },
                                    progress[r.m.uid]?.takeIf { it.durationMs > 0 && it.playlistId == p.id }?.let { it.positionMs.toFloat() / it.durationMs }, r.isFavorite,
                                    onClick = { vm.openFrom("f$i:m:" + r.m.uid, Screen.MovieDetail(p.id, r.m.uid)) },
                                    onLong = { scope.launch { val f = vm.graph.library.toggleFavorite(p.id, PrefKind.MOVIE, r.m.uid); vm.show(if (f) "Ajouté aux favoris" else "Retiré des favoris") } },
                                    modifier = Modifier.returnFocus(vm, "f$i:m:" + r.m.uid))
                            }
                        }
                    }
                    if (resume.isNotEmpty() || shelves.isNotEmpty())
                        item(key = "all", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SectionTitle("Ajoutés récemment") }
                }
                items(list, key = { it.m.id }) { r ->
                    val h = progress[r.m.uid]?.takeIf { it.playlistId == p.id }
                    PosterTile(r.m.name, r.m.poster, listOfNotNull(r.m.year, r.m.rating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null },
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
    val shelves = memoState(vm, "shelves:s:${p.id}", p.id, changes, lastSeries) { vm.graph.discover.series(p.id, history) }
    val featured = cat == ALL && filter.isBlank()
    KeepTop(grid, if (featured) headerCount(resume.isNotEmpty(), shelves.size) else 0)
    val phone = com.lumentv.ui.LocalPhone.current
    androidx.compose.runtime.DisposableEffect(phone, cat) {
        vm.backHook = if (!phone && cat != ALL) ({ cat = ALL; vm.seriesCategory = ALL; true }) else null
        onDispose { vm.backHook = null }
    }
    if (!phone && featured) {
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
        VodHome(vm, "hero:s:${p.id}", rows) {
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
        CategoryColumn("Séries", cats, listOf(ALL to (if (phone) "Toutes les séries" else "Accueil des séries"), RECENT2 to "Toutes les séries", RECENT to "Ajoutées récemment").let { if (phone) it.filter { e -> e.first != RECENT2 } else it },
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
                if (featured) {
                    if (resume.isNotEmpty()) item(key = "resume", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { ResumeRow(vm, resume, true) }
                    shelves.forEachIndexed { i, sh ->
                        item(key = "shelf$i", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            ShelfRow(sh.title, sh.items, { it.s.uid }) { r ->
                                PosterTile(r.s.name, r.s.cover, listOfNotNull(r.s.year, r.s.rating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null }, null, r.isFavorite,
                                    onClick = { vm.openFrom("f$i:s:" + r.s.uid, Screen.SeriesDetail(p.id, r.s.uid)) },
                                    onLong = { scope.launch { val f = vm.graph.library.toggleFavorite(p.id, PrefKind.SERIES, r.s.uid); vm.show(if (f) "Ajoutée aux favoris" else "Retirée des favoris") } },
                                    modifier = Modifier.returnFocus(vm, "f$i:s:" + r.s.uid))
                            }
                        }
                    }
                    if (resume.isNotEmpty() || shelves.isNotEmpty())
                        item(key = "all", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SectionTitle("Toutes les séries") }
                }
                items(list, key = { it.s.id }) { r ->
                    PosterTile(r.s.name, r.s.cover, listOfNotNull(r.s.year, r.s.rating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null }, null, r.isFavorite,
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
    if (!com.lumentv.ui.LocalPhone.current) return MovieDetailTv(vm, playlistId, uid)
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
    if (!phone) return SeriesDetailTv(vm, playlistId, uid, s, eps, error, seasons, season, { season = it }, progress, ::watched,
        { l, on -> markWatched(l, on) }, { epMenu = it })
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
    @Suppress("UNCHECKED_CAST")
    val items by produceState(vm.memo["resume:s:$pid"] as? List<ResumeItem> ?: emptyList(), history, pid) {
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
            }.take(com.lumentv.core.Resume.LIMIT).also { vm.memo["resume:s:$pid"] = it }
    }
    return items
}

/** The row above the posters; a long press on one takes it out (and forgets where it was). */
@Composable
private fun ResumeRow(vm: MainViewModel, items: List<ResumeItem>, series: Boolean) {
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
    }
    removing?.let { r ->
        com.lumentv.ui.components.ConfirmDialog("Retirer « ${r.title} » ?",
            if (series) "La série quitte « Reprendre la lecture ». Les épisodes ne seront plus marqués comme vus."
            else "Le film quitte « Reprendre la lecture » et repartira du début.", "Retirer",
            onConfirm = { scope.launch { r.forget.forEach { vm.graph.library.deleteHistory(it) } } }, onDismiss = { removing = null })
    }
}

// ---- Featured rows ----------------------------------------------------------------------------------------------

/** A row of posters above the list ("Nouveautés", "Les mieux notés"…). */
@Composable
private fun <T> ShelfRow(title: String, items: List<T>, key: (T) -> String, tile: @Composable (T) -> Unit) {
    val phone = com.lumentv.ui.LocalPhone.current
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(gridGap()), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
            items(items, key = key) { r -> Box(Modifier.width(if (phone) 104.dp else 150.dp)) { tile(r) } }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.padding(top = 4.dp))

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

/** Items above the posters: the resume row, the featured rows and the title of the full list. */
private fun headerCount(resume: Boolean, shelves: Int) = (if (resume) 1 else 0) + shelves + (if (resume || shelves > 0) 1 else 0)

/**
 * Rows arriving after the posters: the grid keeps its first poster on top, below them. Back to the top then; a grid
 * scrolled further (back from a film page) stays where it is.
 */
@Composable
private fun KeepTop(grid: androidx.compose.foundation.lazy.grid.LazyGridState, headers: Int) {
    var before by remember { androidx.compose.runtime.mutableIntStateOf(headers) }
    LaunchedEffect(headers) {
        if (headers > before && grid.firstVisibleItemIndex in 1..headers && grid.firstVisibleItemScrollOffset == 0) grid.scrollToItem(0)
        before = headers
    }
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
private class HeroItem(
    val key: String, val title: String, val meta: List<String>, val plot: String?, val image: String?,
    val movie: com.lumentv.data.db.MovieEntity? = null, val playLabel: String = "Lecture",
    val play: () -> Unit, val more: () -> Unit,
    /** For the title's logo (TMDB). */
    val series: Boolean = false, val tmdb: String? = null, val year: Int? = null,
    /** The provider's rating, shown when TMDB does not know the title. */
    val rating: String? = null,
)

private class HomeTile(val key: String, val title: String, val image: String?, val progress: Float?, val hero: HeroItem,
                       val onClick: () -> Unit, val onLong: (() -> Unit)? = null)

private class HomeRow(val key: String, val title: String, val tiles: List<HomeTile>)

/** "★ 7,3", "2025", "2 h 09", "Action, Aventure" */
private fun metaOf(rating: String?, year: String?, vararg more: String?): List<String> =
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
@Composable
private fun VodHome(vm: MainViewModel, memoKey: String, rows: List<HomeRow>, actions: @Composable () -> Unit) {
    val first = rows.firstOrNull { it.tiles.isNotEmpty() }?.tiles?.firstOrNull()?.hero
    // The banner follows the focused poster, a moment after it stops moving (no flicker while scrolling).
    var focusedHero by remember { mutableStateOf(vm.memo[memoKey] as? HeroItem) }
    var pending by remember { mutableStateOf<HeroItem?>(null) }
    LaunchedEffect(pending) { pending?.let { delay(280); focusedHero = it; vm.memo[memoKey] = it } }
    val hero = focusedHero ?: first
    val info by produceState<XVodInfo?>(hero?.movie?.let { vm.graph.artwork.cached(it) }, hero?.key) {
        value = hero?.movie?.let { vm.graph.artwork.movie(it) }
    }
    ScreenBackdrop(info?.backdrop ?: hero?.image)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Going down the rows, the banner shrinks to its title: more rows on screen.
    var focusedRow by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    var rowsFocused by remember { mutableStateOf(false) }
    val collapsed = rowsFocused && focusedRow > 0
    Column(Modifier.fillMaxSize()) {
        if (hero != null) Hero(vm, hero, info, actions, collapsed)
        // Rows arriving late (resume, featured) go above the categories: the list then shows the top, unless it is in use.
        var inUse by remember { mutableStateOf(false) }
        val shown = rows.filter { it.tiles.isNotEmpty() }
        // Only when a row really comes first (not on return from a film: the list then keeps its place and the poster its focus).
        var firstKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(shown.firstOrNull()?.key) {
            val k = shown.firstOrNull()?.key
            if (k != firstKey) { if (firstKey != null && !inUse && vm.returnKey == null) list.scrollToItem(0); firstKey = k }
        }
        LazyColumn(Modifier.fillMaxSize().onFocusChanged { inUse = it.hasFocus; rowsFocused = it.hasFocus }, state = list, verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 30.dp)) {
            shown.forEachIndexed { rowIndex, row ->
                item(key = row.key) {
                    Column {
                        Text(row.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                            items(row.tiles, key = { it.key }) { t -> HomePoster(vm, t) { pending = t.hero; focusedRow = rowIndex } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(vm: MainViewModel, h: HeroItem, info: XVodInfo?, actions: @Composable () -> Unit, collapsed: Boolean) {
    val height by androidx.compose.animation.core.animateDpAsState(if (collapsed) 96.dp else 232.dp, label = "hero")
    Column(Modifier.fillMaxWidth().height(height).padding(start = 4.dp, top = 8.dp), verticalArrangement = Arrangement.Bottom) {
        val t = tmdbInfo(vm, h.title, h.series, h.tmdb ?: info?.tmdb, h.year)
        TitleArt(h.title, t?.logo, if (collapsed) 22.sp else 30.sp, if (collapsed) 46.dp else 84.dp)
        val meta = listOfNotNull(ratingText(t, h.rating ?: info?.rating)) + h.meta + listOfNotNull(info?.durationSecs?.takeIf { it > 0 }?.let { duration(it) }, info?.genre?.split(',')?.take(2)?.joinToString(" • ") { it.trim() })
        if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Palette.Text.copy(alpha = 0.85f),
            maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        if (!collapsed) (info?.plot ?: h.plot)?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Text.copy(alpha = 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.52f).padding(top = 6.dp))
        }
        if (!collapsed) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            HeroButton("▶  " + h.playLabel, primary = true, onClick = h.play)
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
private fun HomePoster(vm: MainViewModel, t: HomeTile, onFocus: () -> Unit) {
    FocusSurface(t.onClick, Modifier.width(104.dp).aspectRatio(2f / 3f).returnFocus(vm, t.key).onFocusChanged { if (it.isFocused) onFocus() },
        onLongClick = t.onLong, focusedScale = 1.08f, shape = RoundedCornerShape(10.dp)) {
        Poster(t.image, t.title, Modifier.fillMaxSize())
        t.progress?.takeIf { it > 0f }?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp)) }
    }
}

// ---- V2 pages of a film and a series (TV) -----------------------------------------------------------------------

/** "Breach (2026) VOSTFR" → "VOSTFR", "The Dog Stars (2026) 4K MULTI" → "4K MULTI": what tells a copy from another. */
private fun versionLabel(name: String): String =
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

    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 4.dp, top = 40.dp)) {
        val t = tmdbInfo(vm, m.m.name, false, info?.tmdb, com.lumentv.core.Featured.yearOf(m.m.year, m.m.name))
        TitleArt(m.m.name, t?.logo, 36.sp, 110.dp)
        val meta = listOfNotNull(ratingText(t, m.m.rating)) + metaOf(null, com.lumentv.core.Featured.yearOf(m.m.year ?: info?.releaseDate, m.m.name)?.toString(),
            info?.durationSecs?.takeIf { it > 0 }?.let { duration(it) }, info?.genre?.split(',')?.take(3)?.joinToString(" • ") { it.trim() })
        Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = Palette.Text.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        Text(info?.plot ?: "Aucun résumé disponible.", style = MaterialTheme.typography.bodyLarge, color = Palette.Text.copy(alpha = 0.92f), maxLines = 4,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.55f).padding(top = 10.dp))
        if (resumable) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressLine(h!!.positionMs.toFloat() / h.durationMs.coerceAtLeast(1), Modifier.width(220.dp))
            Text("  " + (com.lumentv.core.Resume.remaining(h.positionMs, h.durationMs) ?: ""), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (resumable) {
                HeroButton("▶  Reprendre à ${clock(h!!.positionMs)}", primary = true, modifier = Modifier.focusRequester(focus)) { PlayerLauncher.movie(context, playlistId, uid, true) }
                HeroButton("Depuis le début") { PlayerLauncher.movie(context, playlistId, uid, false) }
            } else HeroButton("▶  Lecture", primary = true, modifier = Modifier.focusRequester(focus)) { PlayerLauncher.movie(context, playlistId, uid, false) }
            if (versions.size > 1) HeroButton("Version : ${versionLabel(m.m.name)}  ▾") { versionMenu = true }
            HeroButton(if (m.isFavorite) "★  Dans ma liste" else "+  Ma liste") { scope.launch { vm.graph.library.toggleFavorite(playlistId, PrefKind.MOVIE, uid) } }
            HeroButton(when (dl?.status) {
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
        info?.director?.takeIf { it.isNotBlank() }?.let { Text("Réalisation : $it", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(top = 22.dp)) }
        info?.cast?.takeIf { it.isNotBlank() }?.let { Text("Avec : $it", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
    val last = progress.values.maxByOrNull { it.watchedAt }
    // The episode "Lecture" starts: the one left halfway, else the one after the last seen, else the first.
    val next: Pair<EpisodeEntity, Boolean>? = eps?.let { list ->
        val l = last?.let { h -> list.firstOrNull { it.uid == h.uid } }
        when {
            l != null && !watched(l) -> l to true
            l != null -> list.getOrNull(list.indexOf(l) + 1)?.let { it to false } ?: (list.first() to false)
            else -> list.firstOrNull()?.let { it to false }
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 4.dp, top = 24.dp)) {
        val t = tmdbInfo(vm, s.s.name, true, s.s.tmdb, com.lumentv.core.Featured.year(s.s.year))
        TitleArt(s.s.name, t?.logo, 36.sp, 110.dp)
        Text((listOfNotNull(ratingText(t, s.s.rating)) + metaOf(null, s.s.year, if (seasons.isNotEmpty()) "${seasons.size} saison" + if (seasons.size > 1) "s" else "" else null, s.s.category)).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyLarge, color = Palette.Text.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        s.s.plot?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.Text.copy(alpha = 0.92f), maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.55f).padding(top = 10.dp)) }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            next?.let { (e, resume) ->
                HeroButton("▶  " + (if (resume) "Reprendre" else "Lecture") + " S${e.season} E${e.episode}", primary = true, modifier = Modifier.focusRequester(focus)) {
                    PlayerLauncher.episode(context, playlistId, uid, e.uid, resume)
                }
            }
            HeroButton(if (s.isFavorite) "★  Dans ma liste" else "+  Ma liste", modifier = if (next == null) Modifier.focusRequester(focus) else Modifier) {
                scope.launch { vm.graph.library.toggleFavorite(playlistId, PrefKind.SERIES, uid) }
            }
            eps?.filter { it.season == season }?.takeIf { it.isNotEmpty() }?.let { list ->
                val all = list.all { watched(it) }
                HeroButton(if (all) "Saison $season : non vue" else "Saison $season vue") { markWatched(list, !all) }
            }
        }
        if (seasons.size > 1) Row(Modifier.padding(top = 18.dp).clip(RoundedCornerShape(26.dp)).background(Color(0x99141416)).padding(4.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(seasons) { n ->
                    FocusSurface({ onSeason(n) }, shape = RoundedCornerShape(22.dp), container = if (n == season) Color(0x33FFFFFF) else Color.Transparent, focusedScale = 1.04f) {
                        Text("Saison $n", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    }
                }
            }
        }
        when {
            eps == null -> Loading("Chargement des épisodes…")
            eps.isEmpty() -> EmptyState("Aucun épisode", error ?: "Le fournisseur ne liste aucun épisode pour cette série.")
            else -> LazyRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                items(eps.filter { it.season == season }, key = { it.id }) { e ->
                    val h = progress[e.uid]
                    Column(Modifier.width(200.dp)) {
                        FocusSurface({ PlayerLauncher.episode(context, playlistId, uid, e.uid, h != null && h.positionMs > 30_000 && !watched(e)) },
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f), onLongClick = { onMenu(e) }, focusedScale = 1.06f, shape = RoundedCornerShape(10.dp)) {
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
            modifier = Modifier.padding(top = 6.dp))
    }
    LaunchedEffect(s.s.id, next != null) { delay(80); runCatching { focus.requestFocus() } }
}

/** What TMDB says of a title (logo, rating), asked in the background and kept for the session. */
@Composable
private fun tmdbInfo(vm: MainViewModel, title: String, series: Boolean, tmdb: String?, year: Int?): com.lumentv.data.TitleLogos.Info? {
    val clean = title.substringBefore(" (").substringBefore(" [").trim()
    val info by produceState(vm.graph.logos.cached(series, tmdb, clean, year), series, tmdb, clean, year) {
        value = withContext(Dispatchers.IO) { vm.graph.logos.info(series, tmdb, clean, year) }
    }
    return info
}

/** "★ 7,3" from TMDB when it knows the title, else the provider's rating. */
private fun ratingText(tmdb: com.lumentv.data.TitleLogos.Info?, provider: String?): String? =
    (tmdb?.rating ?: com.lumentv.core.Featured.rating(provider))?.let { "★ " + "%.1f".format(java.util.Locale.FRANCE, it) }

/**
 * The title as its poster writes it (the logo, from TMDB) when there is one, else in big letters. The text shows
 * while the logo is looked for, so nothing jumps when there is none.
 */
@Composable
private fun TitleArt(title: String, logo: String?, size: androidx.compose.ui.unit.TextUnit, logoHeight: androidx.compose.ui.unit.Dp) {
    val clean = title.substringBefore(" (").substringBefore(" [").trim()
    androidx.compose.animation.Crossfade(logo, label = "title") { url ->
        if (url != null) coil.compose.AsyncImage(url, clean, contentScale = androidx.compose.ui.layout.ContentScale.Fit, alignment = Alignment.BottomStart,
            modifier = Modifier.height(logoHeight).fillMaxWidth(0.42f))
        else Text(clean, fontSize = size, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = size * 1.12f, modifier = Modifier.fillMaxWidth(0.6f))
    }
}
