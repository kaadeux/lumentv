@file:OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.lumentv.desktop

import com.lumentv.data.ratingText
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.core.Featured
import com.lumentv.data.TitleLogos
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- TMDB pieces, shared by the homes and the pages ------------------------------------------------------------

/** Logo, TMDB rating, French summary and cast of a title, from the cache at once, else asked for. */
@Composable
fun rememberInfo(state: AppState, series: Boolean, name: String, tmdb: String?, year: Int?): TitleLogos.Info? {
    val info by produceState(state.vod.cachedInfo(series, name, tmdb, year), series, name, tmdb, year) {
        value = state.vod.info(series, name, tmdb, year)
    }
    return info
}

/** The title as its poster writes it (TMDB's logo) when there is one, else in big letters. */
@Composable
fun TitleArt(title: String, logo: String?, size: TextUnit, logoHeight: Dp) {
    val clean = Featured.shortTitle(title)
    // The text shows while the logo loads, and stays when there is none or it does not load.
    var loaded by remember(logo) { mutableStateOf(false) }
    Box(contentAlignment = Alignment.BottomStart) {
        if (!loaded) Text(clean, color = Pal.text, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = size * 1.1f)
        if (logo != null) AsyncImage(logo, clean, Modifier.heightIn(max = logoHeight).fillMaxWidth(0.42f).alpha(if (loaded) 1f else 0f), contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            onState = { loaded = it is coil3.compose.AsyncImagePainter.State.Success })
    }
}

/** The viewer's own rating, 1 to 5 stars (shared with the TV by the profile). The star already given takes it away. */
@Composable
fun StarRating(stars: Int, onRate: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Votre note", color = Pal.dim, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        (1..5).forEach { n ->
            Icon(if (n <= stars) Icons.Filled.Star else Icons.Filled.StarBorder, "$n étoile" + if (n > 1) "s" else "",
                tint = if (n <= stars) Color(0xFFFFC94D) else Pal.dim,
                modifier = Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).combinedClickable { onRate(if (n == stars) 0 else n) }.padding(2.dp))
        }
    }
}

/** The picture behind a banner or a page: on the right, fading into the background to the left and the bottom. */
@Composable
fun BannerPicture(image: String?, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height)) {
        Crossfade(image, label = "backdrop") { url ->
            AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Pal.bg, 0.45f to Pal.bg.copy(alpha = 0.75f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Pal.bg)))
    }
}

// ---- The home ----------------------------------------------------------------------------------------------------

/** What the banner shows: the title the mouse is on. */
class HeroItem(
    val key: String, val name: String, val series: Boolean, val tmdb: String?, val year: Int?,
    val poster: String?, val meta: List<String>, val plot: String?, val rating: String?,
    val playLabel: String, val play: () -> Unit, val more: () -> Unit,
    /** The wide picture (asked for when shown); the poster until then. */
    val backdrop: suspend () -> String? = { null },
) {
    /** The same title, played another way (Reprendre la lecture: the episode in progress or the next one). */
    fun playing(label: String, play: () -> Unit) = HeroItem(key, name, series, tmdb, year, poster, meta, plot, rating, label, play, more, backdrop)
}

class HomeTile(val key: String, val title: String, val image: String?, val caption: String?, val progress: Float?, val favorite: Boolean,
               val hero: HeroItem, val onClick: () -> Unit, val onRemove: (() -> Unit)? = null,
               /** TMDB's poster, when the provider's is missing or does not load. */
               val altImage: (suspend () -> String?)? = null,
               /** The title it has in France (TMDB). */
               val altTitle: (suspend () -> String?)? = null)

class HomeRow(val key: String, val title: String, val tiles: List<HomeTile>)

/**
 * The home of Films or Séries, as on the TV: a banner with the title under the mouse over its picture (Lecture,
 * Plus d'infos, [actions]), then rows of posters (Reprendre la lecture, the TMDB rows, the categories).
 */
@Composable
fun VodHome(state: AppState, rows: List<HomeRow>, list: LazyListState, actions: @Composable () -> Unit) {
    val shown = rows.filter { it.tiles.isNotEmpty() }
    var pending by remember { mutableStateOf<HeroItem?>(null) }
    // The title shown, kept while a page is open over the home (by its key: the rows are made again on return).
    var heroKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // Follows the mouse a moment after it stops on a poster, so moving across a row does not flicker.
    LaunchedEffect(pending) { pending?.let { delay(250); heroKey = it.key } }
    val h = heroKey?.let { k -> shown.firstNotNullOfOrNull { r -> r.tiles.firstOrNull { it.hero.key == k }?.hero } } ?: shown.firstOrNull()?.tiles?.firstOrNull()?.hero
    val backdrop by produceState<String?>(null, h?.key) { value = h?.poster; value = h?.backdrop?.invoke() ?: h?.poster }
    // Rows arriving late (resume, TMDB) go above the categories: the list then shows the top, unless the viewer scrolled.
    var scrolled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(shown.firstOrNull()?.key) { if (!scrolled && list.firstVisibleItemIndex > 0) list.scrollToItem(0) }
    // A click on a poster half off screen does not scroll the list or its row to show it whole: back from its page,
    // the home would no longer be where it was. The wheel and the arrows scroll them.
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides StayPut) {
    Box(Modifier.fillMaxSize()) {
        BannerPicture(backdrop, 420.dp)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(300.dp)) { if (h != null) Hero(state, h, actions) }
            if (shown.isEmpty()) Text("Chargement des rangées…", color = Pal.dim, modifier = Modifier.padding(8.dp))
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll) { scrolled = true }) {
                items(shown, key = { it.key }) { row ->
                    Column {
                        Text(row.title, style = MaterialTheme.typography.titleMedium, color = Pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                        ScrollRow(Modifier.padding(vertical = 8.dp)) {
                            items(row.tiles, key = { it.key }) { t -> HomePoster(t) { pending = t.hero } }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Never scrolls to bring a clicked or focused child into view (see [VodHome]). */
private object StayPut : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

@Composable
private fun Hero(state: AppState, h: HeroItem, actions: @Composable () -> Unit) {
    val t = rememberInfo(state, h.series, h.name, h.tmdb, h.year)
    Column(Modifier.fillMaxSize().padding(start = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.Bottom) {
        TitleArt(com.lumentv.data.frenchTitle(h.name, t), t?.logo, 34.sp, 96.dp)
        val meta = listOfNotNull(ratingText(t, h.rating)) + h.meta
        if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = Pal.text.copy(alpha = 0.85f), maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        (t?.overview ?: h.plot)?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Pal.text.copy(alpha = 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp,
                modifier = Modifier.fillMaxWidth(0.55f).padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(h.playLabel, h.play, Icons.Filled.PlayArrow, primary = true)
            Pill("Plus d'infos", h.more)
            actions()
        }
    }
}

@Composable
private fun HomePoster(t: HomeTile, onHover: () -> Unit) {
    Box(Modifier.width(140.dp).onPointerEvent(PointerEventType.Enter) { onHover() }) {
        Poster(t.title, t.image, t.caption, t.progress, t.favorite, t.altImage, t.altTitle, t.onClick)
        t.onRemove?.let { remove ->
            IconButton(remove, Modifier.align(Alignment.TopStart).padding(4.dp).size(28.dp).clip(RoundedCornerShape(14.dp)).background(Pal.bg.copy(alpha = 0.75f))) {
                Icon(Icons.Filled.Close, "Retirer de Reprendre la lecture", tint = Pal.text, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * A poster: picture, progress, title and its line (year, rating, copy). When the provider's picture is missing or does
 * not load (dead server, image taken off TMDB), [altImage] (TMDB's poster) takes its place, else the title in text.
 */
@Composable
fun Poster(title: String, image: String?, caption: String?, progress: Float?, favorite: Boolean, altImage: (suspend () -> String?)? = null,
           altTitle: (suspend () -> String?)? = null, onClick: () -> Unit) {
    // The title it has in France (TMDB) when it comes, the playlist's meanwhile.
    val shownTitle by produceState(title, title) { altTitle?.let { f -> runCatching { f() }.getOrNull()?.let { value = it } } }
    var failed by remember(image) { mutableStateOf(image.isNullOrBlank()) }
    val alt by produceState<String?>(null, failed, title) { if (failed && altImage != null) value = runCatching { altImage() }.getOrNull() }
    val shown = if (failed) alt else image
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Pal.panel).combinedClickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Pal.panelHigh), contentAlignment = Alignment.Center) {
            if (shown == null) Text(title, color = Pal.dim, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
            else AsyncImage(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                onError = { if (!failed) failed = true })
            if (favorite) Icon(Icons.Filled.Favorite, null, tint = Pal.accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
        }
        progress?.let { LinearProgressIndicator({ it.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(3.dp)) }
        Text(shownTitle, color = Pal.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp))
        Text(caption ?: "", color = Pal.dim, maxLines = 1, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
    }
}

/**
 * A row of posters that scrolls sideways with the mouse: ‹ and › on its edges (shown while the mouse is over the row,
 * when there is more that way) move it by most of its width; Shift + wheel scrolls it too.
 */
@Composable
fun ScrollRow(modifier: Modifier = Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    var over by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0) }
    Box(modifier.fillMaxWidth().onPointerEvent(PointerEventType.Enter) { over = true }.onPointerEvent(PointerEventType.Exit) { over = false }
        .onSizeChanged { width = it.width }) {
        LazyRow(state = row, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
        val step = width * 0.8f
        if (over && row.canScrollBackward) RowArrow(Alignment.CenterStart, Icons.Filled.ChevronLeft, "Précédents") { scope.launch { row.animateScrollBy(-step) } }
        if (over && row.canScrollForward) RowArrow(Alignment.CenterEnd, Icons.Filled.ChevronRight, "Suivants") { scope.launch { row.animateScrollBy(step) } }
    }
}

/** A round "liquid glass" button: a pale translucent disc, a light edge and a highlight on top, brighter under the mouse. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.RowArrow(side: Alignment, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    var hover by remember { mutableStateOf(false) }
    Box(Modifier.align(side).padding(horizontal = 10.dp).offset(y = (-22).dp).size(52.dp)
        .shadow(14.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(CircleShape)
        // A smoky base, so the chevron reads over a light poster too; the glass on top.
        .background(Color.Black.copy(alpha = 0.30f))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (hover) 0.42f else 0.30f), Color.White.copy(alpha = if (hover) 0.20f else 0.12f))))
        .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(40f, 22f), radius = 70f))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.15f))), CircleShape)
        .onPointerEvent(PointerEventType.Enter) { hover = true }.onPointerEvent(PointerEventType.Exit) { hover = false }
        .combinedClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

/** A banner button opening a menu (Catégories ▾). */
@Composable
fun MenuPill(label: String, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Pill(label, { open = true })
    if (open) com.lumentv.ui.kit.KitMenu(label.removeSuffix("▾").trim(), options.map { (text, act) -> com.lumentv.ui.kit.KitOption(text, onClick = act) }, { open = false })
}

// ---- Tiles of films and series -----------------------------------------------------------------------------------

fun ratio(h: com.lumentv.sync.SyncHistory?) = h?.takeIf { it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs }

/** TMDB's poster of a film or a series (French when there is one). */
fun AppState.posterOf(m: Mv): suspend () -> String? = { vod.info(false, m.name, null, Featured.yearOf(m.year, m.name))?.poster }
fun AppState.posterOf(s: Se): suspend () -> String? = { vod.info(true, s.name, s.tmdb, Featured.year(s.year))?.poster }
fun AppState.frenchOf(m: Mv): suspend () -> String? = { vod.info(false, m.name, null, Featured.yearOf(m.year, m.name))?.title }
fun AppState.frenchOf(s: Se): suspend () -> String? = { vod.info(true, s.name, s.tmdb, Featured.year(s.year))?.title }

fun AppState.movieTile(rowKey: String, m: Mv, open: (Mv) -> Unit): HomeTile {
    val h = progress(Kind.MOVIE, m.uid)
    val resumable = h != null && com.lumentv.core.Resume.movieToResume(h.positionMs, h.durationMs)
    val year = Featured.yearOf(m.year, m.name)
    return HomeTile("$rowKey:${m.uid}", Featured.shortTitle(m.name), m.poster, Featured.posterLine(m.year, m.rating, m.name), if (resumable) ratio(h) else null,
        isFavorite(Kind.MOVIE, m.uid),
        HeroItem("m:${m.uid}", m.name, false, null, year, m.poster, listOfNotNull(year?.toString()), null, m.rating,
            if (resumable) "Reprendre" else "Lecture", { playMovie(m) }, { open(m) },
            backdrop = { active?.let { p -> runCatching { library.movieDetails(p, m) }.getOrNull()?.backdrop } }),
        onClick = { open(m) }, altImage = posterOf(m), altTitle = frenchOf(m))
}

fun AppState.seriesTile(rowKey: String, s: Se, open: (Se) -> Unit): HomeTile =
    HomeTile("$rowKey:${s.uid}", Featured.shortTitle(s.name), s.cover, Featured.posterLine(s.year, s.rating, null), null, isFavorite(Kind.SERIES, s.uid),
        HeroItem("s:${s.uid}", s.name, true, s.tmdb, Featured.year(s.year), s.backdrop ?: s.cover, listOfNotNull(s.year?.take(4), s.category), s.plot, s.rating,
            "Voir la série", { open(s) }, { open(s) }, backdrop = { s.backdrop }),
        onClick = { open(s) }, altImage = posterOf(s), altTitle = frenchOf(s))

/**
 * The rows of a home: computed in the background (TMDB), kept while a page is open over the home, and replaced only
 * once the new ones are complete (a row more or less would move the posters under the mouse).
 */
@Composable
fun <T> rememberShelves(vararg keys: Any?, load: () -> kotlinx.coroutines.flow.Flow<List<Shelf<T>>>): List<Shelf<T>> {
    val v by produceState(emptyList<Shelf<T>>(), *keys) {
        val shown = value.isNotEmpty()
        var latest: List<Shelf<T>>? = null
        runCatching { load().collect { rows -> latest = rows; if (!shown) value = rows } }
        latest?.let { value = it }
    }
    return v
}

/** "Reprendre la lecture" removal, asked first. */
@Composable
fun ForgetDialog(title: String, series: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) =
    com.lumentv.ui.kit.KitConfirm("Retirer « $title » ?", if (series) "La série quitte « Reprendre la lecture ». Les épisodes ne seront plus marqués comme vus."
        else "Le film quitte « Reprendre la lecture » et repartira du début.", "Retirer", onConfirm, onDismiss, danger = true)
