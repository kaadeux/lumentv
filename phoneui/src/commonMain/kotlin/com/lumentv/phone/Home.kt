package com.lumentv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.AppCore
import com.lumentv.core.Featured
import com.lumentv.core.IoDispatcher
import com.lumentv.core.nowMillis
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.LibraryRepository.Shelf
import com.lumentv.data.discover.SeeAllState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The rows of the homes, kept while the app is open: back from a title they are there at once (and the scroll with them). */
private object HomeCache {
    val rows = HashMap<String, Any>()
    /** Homes computed to their last row (TMDB's included). */
    val done = HashSet<String>()
}

/** A title of the banner: its picture, what TMDB knows of it, and what its buttons do. */
private class Hero(val key: String, val title: String, val series: Boolean, val tmdb: String?, val year: Int?,
                   val image: String?, val meta: String?, val play: (() -> Unit)?, val open: () -> Unit)

// ---- Films and Séries homes: banner, resume, TMDB rows (as on the TV and the phone) -----------------------------

@Composable
internal fun MoviesHome(core: AppCore, p: PlaylistEntity, player: VideoPlayer, open: (Route) -> Unit) {
    val key = "mv:${p.id}:${p.lastRefresh}"
    @Suppress("UNCHECKED_CAST")
    var shelves by remember(key) { mutableStateOf(HomeCache.rows[key] as List<Shelf<MovieRow>>?) }
    LaunchedEffect(key) {
        // Computed once per list: back from a title the same rows (and so the same place); again after a reload.
        if (key in HomeCache.done) return@LaunchedEffect
        // History and ratings of the moment: the rows are not computed again at each film watched.
        val history = core.library.observeHistory(600).first()
        val ratings = core.library.observeRatings().first()
        core.discover.movies(p.id, history, ratings).collect { list ->
            val rows = withContext(kotlinx.coroutines.Dispatchers.Default) {
                list.map { Shelf(it.title, oneEach(it.items, { m -> m.m.name }, { m -> m.m.category }), seeAll = it.seeAll) }.filter { it.items.isNotEmpty() }
            }
            // The playlist's rows come first, TMDB's after: a shorter first answer does not replace a fuller page.
            if (rows.size >= (shelves?.size ?: 0)) { shelves = rows; HomeCache.rows[key] = rows }
        }
        HomeCache.done += key
    }
    val scope = rememberCoroutineScope()
    val heroes = shelves?.firstOrNull()?.items.orEmpty().take(6).map { m ->
        Hero("mv:" + m.m.id, Featured.shortTitle(m.m.name), false, null, Featured.yearOf(m.m.year, m.m.name), m.m.poster,
            Featured.posterLine(m.m.year, m.m.rating, m.m.name), play = { playMovie(core, player, scope, m) }) { open(Route.Movie(m)) }
    }
    HomeList(core, "Films", heroes, shelves, onCategories = { open(Route.Categories(false)) },
        onSeeAll = { s -> s.seeAll?.let { open(SeeAllRoute(s.title, SeeAllState(it, core.scope) { m -> m.m.id }, null)) } },
        resume = { ResumeRow(core, p, HistoryKind.MOVIE) { h -> scope.launch { core.library.movieByUid(p.id, h.uid)?.let { open(Route.Movie(it)) } } } },
        row = { items -> items(items, key = { it.m.id }) { m -> Box(Modifier.width(104.dp)) { Poster(m.m.poster, Featured.shortTitle(m.m.name), french = core.frenchOf(m)) { open(Route.Movie(m)) } } } })
}

@Composable
internal fun SeriesHome(core: AppCore, p: PlaylistEntity, player: VideoPlayer, open: (Route) -> Unit) {
    val key = "se:${p.id}:${p.lastRefresh}"
    @Suppress("UNCHECKED_CAST")
    var shelves by remember(key) { mutableStateOf(HomeCache.rows[key] as List<Shelf<SeriesRow>>?) }
    LaunchedEffect(key) {
        if (key in HomeCache.done) return@LaunchedEffect
        val history = core.library.observeHistory(600).first()
        val ratings = core.library.observeRatings().first()
        core.discover.series(p.id, history, ratings).collect { list ->
            val rows = withContext(kotlinx.coroutines.Dispatchers.Default) {
                list.map { Shelf(it.title, oneEach(it.items, { s -> s.s.name }, { s -> s.s.category }), it.badges, it.seeAll) }.filter { it.items.isNotEmpty() }
            }
            if (rows.size >= (shelves?.size ?: 0)) { shelves = rows; HomeCache.rows[key] = rows }
        }
        HomeCache.done += key
    }
    val scope = rememberCoroutineScope()
    val badges = remember(shelves) { shelves.orEmpty().flatMap { it.badges.entries }.associate { it.key to it.value } }
    val heroes = shelves?.firstOrNull()?.items.orEmpty().take(6).map { s ->
        Hero("se:" + s.s.id, Featured.shortTitle(s.s.name), true, s.s.tmdb, Featured.yearOf(s.s.year, s.s.name), s.s.backdrop ?: s.s.cover,
            Featured.posterLine(s.s.year, s.s.rating, null), play = null) { open(Route.Series(s)) }
    }
    HomeList(core, "Séries", heroes, shelves, onCategories = { open(Route.Categories(true)) },
        onSeeAll = { s -> s.seeAll?.let { open(SeeAllRoute(s.title, null, SeeAllState(it, core.scope) { x -> x.s.id }, s.badges)) } },
        resume = { ResumeRow(core, p, HistoryKind.EPISODE) { h -> scope.launch { h.parentUid?.let { core.library.seriesByUid(p.id, it) }?.let { open(Route.Series(it)) } } } },
        row = { items -> items(items, key = { it.s.id }) { s -> Box(Modifier.width(104.dp)) {
            Poster(s.s.cover, Featured.shortTitle(s.s.name), french = core.frenchOf(s)) { open(Route.Series(s)) }
            // "Nouvelle saison", "Nouvel épisode": a series followed with something new.
            badges[s.s.id]?.let { com.lumentv.ui.kit.KitBadge(it, Modifier.align(Alignment.TopStart).padding(5.dp)) }
        } } })
}

/** Plays a film from the banner: where it was left, else from the start; the position is kept on closing. */
internal fun playMovie(core: AppCore, player: VideoPlayer, scope: kotlinx.coroutines.CoroutineScope, row: MovieRow) = scope.launch {
    val m = row.m
    val key = LibraryRepository.key(HistoryKind.MOVIE, m.playlistId, m.uid)
    val h = core.library.history(key)
    val from = h?.takeIf { it.durationMs > 0 && it.positionMs in 60_000 until (it.durationMs * 0.95).toLong() }?.positionMs ?: 0
    play(core, player, core.resolver.movie(m), Featured.shortTitle(m.name), Featured.versionLabel(m.name).takeIf { it != "Version" }, from, m.playlistId) { pos, dur ->
        scope.launch {
            val done = dur > 0 && pos > dur * 0.95
            core.library.record(HistoryEntity(key, HistoryKind.MOVIE, m.playlistId, m.uid, m.name, null, m.poster, null, nowMillis(), if (done) dur else pos, dur))
        }
    }
}

@Composable
private fun <T> HomeList(
    core: AppCore, title: String, heroes: List<Hero>, shelves: List<Shelf<T>>?, onCategories: () -> Unit, onSeeAll: (Shelf<T>) -> Unit,
    resume: @Composable () -> Unit, row: androidx.compose.foundation.lazy.LazyListScope.(List<T>) -> Unit,
) = LazyColumn(state = rememberList("home:$title", ready = shelves != null), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
    if (shelves == null) item { Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    if (heroes.isNotEmpty()) item { Banner(core, heroes) }
    // The Android phone's shortcuts under the banner.
    item(key = "actions") {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { PhoneChip("Catégories  ▾", onClick = onCategories) }
            item { PhoneChip(if (title == "Séries") "Toutes les séries" else "Tous les films", onClick = onCategories) }
        }
    }
    item { resume() }
    shelves.orEmpty().forEach { s ->
        item(key = "t:" + s.title) {
            // A row with more than it shows: its title opens "Tout voir" ("Tout voir ›" on the right).
            val more = s.seeAll?.let { it.load != null || it.all.size > s.items.size } == true
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().then(if (more) Modifier.clickable { onSeeAll(s) } else Modifier)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (more) {
                    Text("Tout voir ›", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        item(key = "r:" + s.title) {
            LazyRow(state = rememberList("row:$title:${s.title}"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) { row(s.items) }
        }
    }
}

/** The banner: the first titles of the top row, one after the other every 6 s, with TMDB's logo and the buttons. */
@Composable
private fun Banner(core: AppCore, heroes: List<Hero>) {
    val pager = rememberPagerState { heroes.size }
    // As on the Android phone: the dash of the title shown fills up in 6 s, then the next title comes (back to the
    // first after the last). A finger moving the banner starts the wait again on the title it lands on.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(heroes.size) {
        progress.snapTo(0f)
        if (heroes.size < 2) return@LaunchedEffect
        while (true) {
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            val start = pager.currentPage
            progress.snapTo(0f)
            val full = coroutineScope {
                val touched = async { snapshotFlow { pager.isScrollInProgress || pager.currentPage != start }.first { it }; false }
                val filled = async { progress.animateTo(1f, tween(6_000, easing = LinearEasing)); true }
                select { touched.onAwait { it }; filled.onAwait { it } }.also { touched.cancel(); filled.cancel() }
            }
            if (full) pager.animateScrollToPage((start + 1) % heroes.size, animationSpec = tween(600))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 10.dp, key = { heroes[it].key }) { page ->
            val h = heroes[page]
            val logo by produceState(core.logos.cached(h.series, h.tmdb, h.title, h.year), h.key) {
                value = withContext(IoDispatcher) { runCatching { core.logos.info(h.series, h.tmdb, h.title, h.year) }.getOrNull() }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(18.dp)).background(Palette.Panel).clickable(onClick = h.open)) {
                AsyncImage(logo?.poster ?: h.image, h.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Palette.Background)))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val l = logo?.logo
                    if (l != null) AsyncImage(l, h.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth(0.75f).height(70.dp))
                    else Text(com.lumentv.data.frenchTitle(h.title, logo), fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(logo?.rating?.let { "★ " + com.lumentv.core.oneDecimal(it) }, h.meta).joinToString("  ·  ")
                    if (meta.isNotEmpty()) Text(meta, color = Palette.Text.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (h.play != null) PhoneButton("▶  Lecture", primary = true, modifier = Modifier.weight(1f), onClick = h.play)
                        PhoneButton(if (h.series) "▶  Épisodes" else "ⓘ  Infos", primary = h.play == null, modifier = Modifier.weight(1f), onClick = h.open)
                    }
                }
            }
        }
        if (heroes.size > 1) Row(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(heroes.size) { i ->
                val on = i == pager.currentPage
                val width by animateDpAsState(if (on) 22.dp else 6.dp, label = "dot")
                Box(Modifier.size(width, 6.dp).clip(CircleShape).background(Palette.TextDim.copy(alpha = 0.5f))
                    .then(if (on) Modifier.drawBehind { drawRect(Palette.Text, size = size.copy(width = size.width * progress.value)) } else Modifier))
            }
        }
    }
}

// ---- Profile ----------------------------------------------------------------------------------------------------

internal fun clock(t: Long): String = Instant.fromEpochMilliseconds(t).toLocalDateTime(TimeZone.currentSystemDefault()).let {
    it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0')
}

internal fun day(t: Long): String = Instant.fromEpochMilliseconds(t).toLocalDateTime(TimeZone.currentSystemDefault()).let {
    it.dayOfMonth.toString().padStart(2, '0') + "/" + it.monthNumber.toString().padStart(2, '0') + " " + clock(t)
}

@Composable
internal fun Toggle(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) = com.lumentv.ui.kit.KitToggle(title, subtitle, on, onChange)

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) = com.lumentv.ui.kit.SettingsSection(title, content = content)

