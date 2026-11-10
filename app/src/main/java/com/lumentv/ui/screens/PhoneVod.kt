@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lumentv.ui.screens

import com.lumentv.data.ratingText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PrefKind
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.db.TransferKind
import com.lumentv.data.db.TransferStatus
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.Screen
import com.lumentv.ui.components.EmptyState
import com.lumentv.ui.components.Loading
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.Poster
import com.lumentv.ui.components.ProgressLine
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async

// ---- Films / Séries on the phone: a banner to swipe, then rows of posters, as on a streaming app -------------------

/** How long the banner shows a title before the next one. */
private const val HERO_MS = 6_000

/** A shortcut under the banner ("Catégories ▾", "Tous les films"…). */
internal class PhoneAction(val label: String, val onClick: () -> Unit)

/**
 * The phone's home of Films or Séries: the first titles of the first row as a banner (swipe for the next ones), the
 * shortcuts, then the same rows as on the TV. A tap opens a title's page, a long press adds it to "Ma liste" (or takes
 * it out of "Reprendre la lecture").
 */
@Composable
internal fun PhoneVodHome(vm: MainViewModel, memoKey: String, rows: List<HomeRow>, actions: List<PhoneAction>) {
    val shown = rows.filter { it.tiles.isNotEmpty() }
    // The banner: the first titles TMDB puts forward (not the resume row, already a row of its own).
    val heroes = remember(shown.map { it.key }) {
        (listOf("Recommandé pour vous", "Tendances du moment").firstNotNullOfOrNull { t -> shown.firstOrNull { it.title == t } }
            ?: shown.firstOrNull { it.key.startsWith("shelf") && it.title != com.lumentv.data.discover.DiscoverEngine.NEW_EPISODES } ?: shown.firstOrNull())?.tiles?.take(5).orEmpty()
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(
        (vm.memo["$memoKey:index"] as? Int) ?: 0, (vm.memo["$memoKey:offset"] as? Int) ?: 0)
    // Back from a title's page: the list is where it was.
    androidx.compose.runtime.DisposableEffect(list) {
        onDispose { vm.memo["$memoKey:index"] = list.firstVisibleItemIndex; vm.memo["$memoKey:offset"] = list.firstVisibleItemScrollOffset }
    }
    // The banner starts moving once every row is there: no row arrived for 1.5 s (TMDB's rows come in steps).
    var ready by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(shown.sumOf { it.key.hashCode() * 31 + it.tiles.size }) {
        ready = false; kotlinx.coroutines.delay(1_500); ready = true
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (heroes.isNotEmpty()) item(key = "hero") { PhoneHero(vm, heroes, ready) }
        if (actions.isNotEmpty()) item(key = "actions") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                items(actions, key = { it.label }) { a -> PhoneChip(a.label, onClick = a.onClick) }
            }
        }
        items(shown, key = { it.key }) { row -> PhoneRow(row) }
    }
}

/** The banner: the picture of the title, its logo, rating and two buttons; a swipe shows the next title. */
@Composable
private fun PhoneHero(vm: MainViewModel, heroes: List<HomeTile>, ready: Boolean) {
    val pager = rememberPagerState { heroes.size }
    // Goes to the next title by itself every 6 s (back to the first after the last), once the page has loaded ([ready]):
    // the dash under the banner fills up meanwhile. A finger moving the banner starts the wait again on the title it
    // lands on. Stops once the banner leaves the screen (the list drops it); the fill is only redrawn, not recomposed.
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(ready, heroes.size) {
        progress.snapTo(0f)
        if (!ready || heroes.size < 2) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.snapshotFlow { pager.isScrollInProgress }.first { !it }
            val start = pager.currentPage
            progress.snapTo(0f)
            // The wait ends full (move on), or early when a finger moves the banner (start again).
            val full = kotlinx.coroutines.coroutineScope {
                val touched = async { androidx.compose.runtime.snapshotFlow { pager.isScrollInProgress || pager.currentPage != start }.first { it }; false }
                val filled = async { progress.animateTo(1f, androidx.compose.animation.core.tween(HERO_MS, easing = androidx.compose.animation.core.LinearEasing)); true }
                kotlinx.coroutines.selects.select { touched.onAwait { it }; filled.onAwait { it } }.also { touched.cancel(); filled.cancel() }
            }
            if (full) pager.animateScrollToPage((start + 1) % heroes.size, animationSpec = androidx.compose.animation.core.tween(600))
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 10.dp, key = { heroes[it].key }) { page ->
            val h = heroes[page].hero
            val info by produceState(h.movie?.let { vm.graph.artwork.cached(it) }, h.key) { value = h.movie?.let { vm.graph.artwork.movie(it) } }
            val t = tmdbInfo(vm, h.title, h.series, h.tmdb ?: info?.tmdb, h.year)
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(18.dp)).background(Palette.Panel)
                .combinedClickable(onClick = h.more, onLongClick = heroes[page].onLong)) {
                Backdrop(info?.backdrop ?: h.image, Modifier.fillMaxSize(), fade = 0.35f)
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Logo(h.title, t?.logo, 70.dp, 26)
                    val meta = listOfNotNull(ratingText(t, h.rating ?: info?.rating)) + h.meta +
                        listOfNotNull(info?.genre?.split(',')?.take(2)?.joinToString(" • ") { it.trim() })
                    if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Palette.Text.copy(alpha = 0.85f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PhoneButton("▶  " + h.playLabel, primary = true, modifier = Modifier.weight(1f), onClick = h.play)
                        PhoneButton("ⓘ  Infos", modifier = Modifier.weight(1f), onClick = h.more)
                    }
                }
            }
        }
        if (heroes.size > 1) Row(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(heroes.size) { i ->
                val on = i == pager.currentPage
                val width by androidx.compose.animation.core.animateDpAsState(if (on) 22.dp else 6.dp, label = "dot")
                Box(Modifier.size(width, 6.dp).clip(CircleShape).background(Palette.TextDim.copy(alpha = 0.5f))
                    .then(if (on) Modifier.drawBehind { drawRect(Palette.Text, size = size.copy(width = size.width * progress.value)) } else Modifier))
            }
        }
    }
}

/** A row: its title, then posters to swipe through. */
@Composable
private fun PhoneRow(row: HomeRow) {
    val resume = row.key == "resume"
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Palette.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (resume) Text("  Appui long pour retirer", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            items(row.tiles, key = { it.key }) { t ->
                Column(Modifier.width(104.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = t.onClick, onLongClick = t.onLong)) {
                        Poster(t.image, t.title, Modifier.fillMaxSize(), t.search)
                        t.progress?.takeIf { it > 0f }?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp)) }
                        t.badge?.let { com.lumentv.ui.kit.KitBadge(it, Modifier.align(Alignment.TopStart).padding(5.dp)) }
                    }
                    // "Reprendre la lecture": which episode, how long is left.
                    if (resume) t.hero.meta.firstOrNull()?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

// ---- A film's page on the phone ----------------------------------------------------------------------------------

@Composable
internal fun PhoneMovieDetail(vm: MainViewModel, playlistId: Long, startUid: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    val dl = transfers.firstOrNull { it.kind == TransferKind.DOWNLOAD && it.playlistId == playlistId && it.refUid == uid }
    val ratings by vm.graph.library.observeRatings().collectAsStateWithLifecycle(emptyList())
    val myStars = ratings.firstOrNull { it.key == LibraryRepository.key(HistoryKind.MOVIE, playlistId, uid) }?.stars ?: 0
    val year = com.lumentv.core.Featured.yearOf(m.m.year ?: info?.releaseDate, m.m.name)
    val t = tmdbInfo(vm, m.m.name, false, info?.tmdb, year)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "top") {
            DetailTop(info?.backdrop ?: m.m.poster, com.lumentv.data.frenchTitle(m.m.name, t), t?.logo)
            Meta(listOfNotNull(ratingText(t, m.m.rating)) + metaOf(null, year?.toString(), info?.durationSecs?.takeIf { it > 0 }?.let { duration(it) }))
            info?.genre?.takeIf { it.isNotBlank() }?.let { Text(it.split(',').take(3).joinToString(" • ") { g -> g.trim() }, style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        item(key = "buttons") {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (resumable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(h!!.positionMs.toFloat() / h.durationMs.coerceAtLeast(1), Modifier.weight(1f))
                        Text("  " + (com.lumentv.core.Resume.remaining(h.positionMs, h.durationMs) ?: ""), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    }
                    PhoneButton("▶  Reprendre à ${clock(h.positionMs)}", primary = true, modifier = Modifier.fillMaxWidth()) { PlayerLauncher.movie(context, playlistId, uid, true) }
                    PhoneButton("↺  Depuis le début", modifier = Modifier.fillMaxWidth()) { PlayerLauncher.movie(context, playlistId, uid, false) }
                } else PhoneButton("▶  Lecture", primary = true, modifier = Modifier.fillMaxWidth()) { PlayerLauncher.movie(context, playlistId, uid, false) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconAction(if (m.isFavorite) "✓" else "+", "Ma liste") { scope.launch { vm.graph.library.toggleFavorite(playlistId, PrefKind.MOVIE, uid) } }
                    IconAction(when (dl?.status) { TransferStatus.DONE -> "▶"; TransferStatus.RUNNING, TransferStatus.SCHEDULED -> "…"; else -> "⬇" },
                        when (dl?.status) {
                            TransferStatus.DONE -> "Téléchargé"
                            TransferStatus.RUNNING -> if (dl.totalBytes > 0) "${dl.bytes * 100 / dl.totalBytes} %" else "En cours"
                            TransferStatus.SCHEDULED -> "En attente"
                            else -> "Télécharger"
                        }) {
                        when (dl?.status) {
                            TransferStatus.DONE -> PlayerLauncher.file(context, dl.id)
                            TransferStatus.RUNNING, TransferStatus.SCHEDULED -> vm.openRoot(Screen.Recordings)
                            else -> scope.launch {
                                if (dl != null) vm.graph.transfers.retry(dl.id) else vm.graph.transfers.downloadMovie(m.m)
                                vm.show("Téléchargement lancé · suivez-le dans Téléchargés")
                            }
                        }
                    }
                    if (versions.size > 1) IconAction("⇄", com.lumentv.core.Featured.versionLabel(m.m.name)) { versionMenu = true }
                }
            }
        }
        item(key = "about") {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                if (h != null || myStars > 0) com.lumentv.ui.components.StarRating(myStars, if (myStars > 0) "Votre note" else "Notez ce film",
                    { n -> scope.launch { vm.graph.library.rate(HistoryKind.MOVIE, playlistId, uid, n, m.m.name.substringBefore(" (").trim(), m.m.poster)
                        vm.show(if (n == 0) "Note retirée" else "Note enregistrée : $n/5") } }, Modifier.padding(bottom = 10.dp))
                ExpandableText(t?.overview ?: info?.plot ?: "Aucun résumé disponible.", 4)
                People(t, "Réalisation")
                Text(m.m.category, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (versionMenu) OptionsDialog("Version", versions.map { v ->
        MenuOption(com.lumentv.core.Featured.versionLabel(v.m.name) + (com.lumentv.core.Featured.rating(v.m.rating)?.let { "  ·  ★ $it" } ?: ""), checked = v.m.uid == uid) { uid = v.m.uid }
    }, onDismiss = { versionMenu = false }, subtitle = "La même œuvre en plusieurs qualités ou langues")
}

// ---- A series' page on the phone ---------------------------------------------------------------------------------

/**
 * The episode "Lecture" starts: the furthest one reached (by season and number): itself when left halfway (true), else
 * the one after it; a series seen to its last episode starts again at the first.
 */
internal fun nextEpisode(eps: List<EpisodeEntity>?, progress: Map<String, HistoryEntity>, watched: (EpisodeEntity) -> Boolean): Pair<EpisodeEntity, Boolean>? =
    eps?.let { list ->
        val l = list.filter { it.uid in progress }.maxWithOrNull(compareBy({ it.season }, { it.episode }))
        when {
            l != null && !watched(l) -> l to true
            l != null -> list.getOrNull(list.indexOf(l) + 1)?.let { it to false } ?: (list.first() to false)
            else -> list.firstOrNull()?.let { it to false }
        }
    }

@Composable
internal fun PhoneSeriesDetail(
    vm: MainViewModel, playlistId: Long, uid: String, s: SeriesRow, eps: List<EpisodeEntity>?, error: String?, seasons: List<Int>, season: Int,
    onSeason: (Int) -> Unit, progress: Map<String, HistoryEntity>, watched: (EpisodeEntity) -> Boolean, markWatched: (List<EpisodeEntity>, Boolean) -> Unit,
    onMenu: (EpisodeEntity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ratings by vm.graph.library.observeRatings().collectAsStateWithLifecycle(emptyList())
    val myStars = ratings.firstOrNull { it.key == LibraryRepository.key("se", playlistId, uid) }?.stars ?: 0
    val next = nextEpisode(eps, progress, watched)
    val t = tmdbInfo(vm, s.s.name, true, s.s.tmdb, com.lumentv.core.Featured.year(s.s.year))
    val inSeason = eps?.filter { it.season == season }.orEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "top") {
            DetailTop(s.s.backdrop ?: s.s.cover, com.lumentv.data.frenchTitle(s.s.name, t), t?.logo)
            Meta(listOfNotNull(ratingText(t, s.s.rating)) + metaOf(null, s.s.year,
                if (seasons.isNotEmpty()) "${seasons.size} saison" + (if (seasons.size > 1) "s" else "") else null))
        }
        item(key = "buttons") {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                next?.let { (e, resume) ->
                    PhoneButton("▶  " + (if (resume) "Reprendre" else "Lecture") + " S${e.season} E${e.episode}", primary = true, modifier = Modifier.fillMaxWidth()) {
                        PlayerLauncher.episode(context, playlistId, uid, e.uid, resume)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconAction(if (s.isFavorite) "✓" else "+", "Ma liste") { scope.launch { vm.graph.library.toggleFavorite(playlistId, PrefKind.SERIES, uid) } }
                    if (inSeason.isNotEmpty()) {
                        val all = inSeason.all { watched(it) }
                        IconAction(if (all) "✓" else "○", if (all) "Saison vue" else "Saison $season vue") { markWatched(inSeason, !all) }
                    }
                }
            }
        }
        item(key = "about") {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                if (progress.isNotEmpty() || myStars > 0) com.lumentv.ui.components.StarRating(myStars, if (myStars > 0) "Votre note" else "Notez cette série",
                    { n -> scope.launch { vm.graph.library.rate("se", playlistId, uid, n, s.s.name, s.s.cover); vm.show(if (n == 0) "Note retirée" else "Note enregistrée : $n/5") } },
                    Modifier.padding(bottom = 10.dp))
                (t?.overview ?: s.s.plot)?.takeIf { it.isNotBlank() }?.let { ExpandableText(it, 3) }
                People(t, "Créée par")
            }
        }
        if (seasons.size > 1) item(key = "seasons") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                items(seasons) { n -> PhoneChip("Saison $n", selected = n == season) { onSeason(n) } }
            }
        } else item(key = "gap") { Spacer(Modifier.height(14.dp)) }
        when {
            eps == null -> item(key = "loading") { Loading("Chargement des épisodes…") }
            eps.isEmpty() -> item(key = "empty") { EmptyState("Aucun épisode", error ?: "Le fournisseur ne liste aucun épisode pour cette série.") }
            else -> items(inSeason, key = { it.id }) { e ->
                val h = progress[e.uid]
                val seen = watched(e)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { PlayerLauncher.episode(context, playlistId, uid, e.uid, h != null && h.positionMs > 30_000 && !seen) }, onLongClick = { onMenu(e) })
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                        Poster(e.image ?: s.s.backdrop ?: s.s.cover, e.title, Modifier.fillMaxSize())
                        if (seen) Text("✓ Vu", style = MaterialTheme.typography.labelSmall, color = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 1.dp))
                        if (h != null && h.durationMs > 0 && !seen) ProgressLine(h.positionMs.toFloat() / h.durationMs, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("${e.episode}. " + e.title.substringAfterLast(" - ").ifBlank { e.title }, style = MaterialTheme.typography.titleSmall, color = Palette.Text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (e.durationSecs > 0) Text(duration(e.durationSecs), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).combinedClickable(onClick = { onMenu(e) }), contentAlignment = Alignment.Center) {
                        Text("⋮", fontSize = 22.sp, color = Palette.TextDim)
                    }
                }
                e.plot?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
}

// ---- Pieces ------------------------------------------------------------------------------------------------------

/** The top of a page: the title's picture fading into the page, its logo (or name) over the bottom of it. */
@Composable
private fun DetailTop(image: String?, title: String, logo: String?) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp))) {
        Backdrop(image, Modifier.fillMaxSize(), fade = 0.25f)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Logo(title, logo, 72.dp, 26)
        }
    }
}

@Composable
private fun Meta(parts: List<String>) {
    if (parts.isEmpty()) return
    Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Palette.Text.copy(alpha = 0.85f), maxLines = 2,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** A wide picture, darkened towards the bottom so the text over it reads. Asked at screen size, kept in memory. */
@Composable
private fun Backdrop(url: String?, modifier: Modifier, fade: Float) {
    val context = LocalContext.current
    val shade = remember(fade) { Brush.verticalGradient(0f to Color.Transparent, fade to Color.Transparent, 1f to Palette.Background) }
    Box(modifier.background(Palette.PanelHigh).drawWithContent { drawContent(); drawRect(shade) }) {
        if (!url.isNullOrBlank()) {
            val request = remember(url) { ImageRequest.Builder(context).data(url).size(960, 600).crossfade(180).build() }
            AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The title as its poster writes it (TMDB logo), else in big letters. */
@Composable
private fun Logo(title: String, logo: String?, height: Dp, textSize: Int) {
    val clean = title.substringBefore(" (").substringBefore(" [").trim()
    if (logo != null) AsyncImage(logo, clean, contentScale = ContentScale.Fit, modifier = Modifier.height(height).fillMaxWidth(0.8f))
    else Text(clean, fontSize = textSize.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
        lineHeight = (textSize * 1.15f).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** A big button: white for the main action, glassy otherwise. */
@Composable
internal fun PhoneButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(46.dp).clip(RoundedCornerShape(10.dp)).background(if (primary) Color(0xF2F2F2F2) else Color(0x33FFFFFF))
        .combinedClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (primary) Palette.OnFocus else Palette.Text, maxLines = 1)
    }
}

/** A round icon with its label under it ("+ Ma liste", "⬇ Télécharger"). */
@Composable
private fun IconAction(icon: String, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 22.sp, color = Palette.Text)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(88.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A glassy pill: shortcuts and seasons. */
@Composable
internal fun PhoneChip(label: String, selected: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.height(36.dp).clip(shape).background(if (selected) Color(0xE6F2F2F2) else Color(0x26FFFFFF))
        .border(1.dp, if (selected) Color.Transparent else Color(0x1FFFFFFF), shape).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Palette.OnFocus else Palette.Text, maxLines = 1)
    }
}

/** Who made it ([makersLabel]) and the main actors, from TMDB only: nothing when TMDB does not know the title. */
@Composable
private fun People(t: com.lumentv.data.TitleLogos.Info?, makersLabel: String) {
    t?.makers?.takeIf { it.isNotEmpty() }?.let { Text("$makersLabel : " + it.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(top = 10.dp)) }
    t?.cast?.takeIf { it.isNotEmpty() }?.let { Text("Avec : " + it.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 3,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
}

/** A summary cut to [lines]; a tap shows it all. */
@Composable
private fun ExpandableText(text: String, lines: Int) {
    var open by remember { mutableStateOf(false) }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Text.copy(alpha = 0.92f), maxLines = if (open) 60 else lines, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).combinedClickable(onClick = { open = !open }))
}
