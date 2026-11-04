@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lumentv.ui.components

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.lumentv.ui.theme.Palette

/** A row of buttons on the TV; on a phone they wrap onto several lines instead of running off the screen. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (com.lumentv.ui.LocalPhone.current) androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    else Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** The hint for the device: "Maintenez OK…" on the TV, "Maintenez le doigt…" on a phone. */
@Composable
fun hint(tv: String, phone: String) = if (com.lumentv.ui.LocalPhone.current) phone else tv

/**
 * A row of buttons (or a column) the remote comes back to on the element it left, not on the one level with where it
 * comes from: [focusMemory] on the row, [remembered] on each element. An element that leaves the screen is forgotten.
 */
class FocusMemory {
    var last: androidx.compose.ui.focus.FocusRequester? = null
}

@Composable
fun rememberFocusMemory() = androidx.compose.runtime.remember { FocusMemory() }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.focusMemory(m: FocusMemory): Modifier = this.then(Modifier
    .focusProperties { enter = { m.last ?: androidx.compose.ui.focus.FocusRequester.Default } }
    .focusGroup())

@Composable
fun Modifier.remembered(m: FocusMemory, first: Boolean = false): Modifier {
    val r = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.DisposableEffect(r) {
        if (first && m.last == null) m.last = r
        onDispose { if (m.last === r) m.last = null }
    }
    return this.then(Modifier.focusRequester(r).onFocusChanged { if (it.isFocused) m.last = r })
}

/** 1 to 5 stars the remote moves across; OK gives the note, OK again on the same star removes it. */
@Composable
fun StarRating(stars: Int, label: String, onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    val m = rememberFocusMemory()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextDim, modifier = Modifier.padding(end = 12.dp))
        Row(Modifier.focusMemory(m), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { i ->
                FocusSurface({ onRate(if (i == stars) 0 else i) }, Modifier.remembered(m, first = i == stars.coerceAtLeast(1)),
                    shape = androidx.compose.foundation.shape.CircleShape, container = Color.Transparent, focusedScale = 1.15f) {
                    Text(if (i <= stars) "★" else "☆", fontSize = 26.sp, color = if (i <= stars) Palette.Gold else Palette.TextDim,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        if (stars > 0) Text("  $stars/5", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
    }
}

/** The entry a column is entered on (the category or group chosen); see [enterOn] and [enterHere]. */
class EnterTarget {
    val requester = androidx.compose.ui.focus.FocusRequester()
    /** How many entries are marked (the old choice leaves after the new one arrives): > 0 = on screen. */
    var shown by androidx.compose.runtime.mutableIntStateOf(0)
}

@Composable
fun rememberEnterTarget() = androidx.compose.runtime.remember { EnterTarget() }

/**
 * Entering this column with the remote (Left from the posters, Right from the menu) lands on its [EnterTarget] when
 * it is on screen, not on the entry level with where the focus came from.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.enterOn(t: EnterTarget): Modifier = this.then(Modifier
    .focusProperties { enter = { if (t.shown > 0) t.requester else androidx.compose.ui.focus.FocusRequester.Default } }
    .focusGroup())

/** Marks the entry [enterOn] lands on. */
@Composable
fun Modifier.enterHere(t: EnterTarget): Modifier {
    androidx.compose.runtime.DisposableEffect(t) { t.shown++; onDispose { t.shown-- } }
    return this.then(Modifier.focusRequester(t.requester))
}

/** The poster or line a sub-screen was opened from ([com.lumentv.ui.MainViewModel.openFrom]) takes the focus back on return. */
@Composable
fun Modifier.returnFocus(vm: com.lumentv.ui.MainViewModel, key: String): Modifier {
    if (vm.returnKey != key) return this
    val r = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    var got by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // The screen is put back in place first (lists, rows): try a few times until the poster takes the focus.
        repeat(25) {
            kotlinx.coroutines.delay(60)
            runCatching { r.requestFocus() }
            kotlinx.coroutines.delay(20)
            if (got) { vm.returnKey = null; return@LaunchedEffect }
        }
        vm.returnKey = null
    }
    return this.then(Modifier.focusRequester(r).onFocusChanged { if (it.isFocused) got = true })
}

/** A panel's width on the TV; the whole width on a phone (dialogs, empty states). */
@Composable
fun Modifier.panelWidth(width: Dp): Modifier = if (com.lumentv.ui.LocalPhone.current) fillMaxWidth() else width(width)

/** The one focusable building block: clear white focus, slight zoom, rounded corners. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    container: Color = Palette.Panel,
    focusedContainer: Color = Palette.Focus,
    focusedScale: Float = 1.03f,
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    // Phone: a plain touch target (tap, long press), without the TV's focus zoom and white highlight.
    if (com.lumentv.ui.LocalPhone.current) {
        Box(
            modifier.clip(shape).background(if (selected) Palette.AccentSoft else container)
                .then(if (selected) Modifier.border(1.dp, Palette.Accent, shape) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Palette.Text) { content() }
        }
        return
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.AccentSoft else container,
            contentColor = Palette.Text,
            focusedContainerColor = focusedContainer,
            focusedContentColor = Palette.OnFocus,
            pressedContainerColor = focusedContainer,
            pressedContentColor = Palette.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, Palette.Accent), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Palette.Focus), shape = shape),
        ),
        content = content,
    )
}

@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        container = if (primary) Palette.Accent else Palette.PanelHigh,
        focusedScale = 1.05f,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = if (com.lumentv.ui.LocalPhone.current) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    // Phone: the title is already in the bar at the top; only the buttons beside it stay.
    if (com.lumentv.ui.LocalPhone.current) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { trailing() }
        return
    }
    Row(modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineMedium, color = Palette.Text, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.panelWidth(520.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim, textAlign = TextAlign.Center)
            if (action != null) { Spacer(Modifier.height(20.dp)); action() }
        }
    }
}

@Composable
fun Loading(text: String = "Chargement…", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
    }
}

@Composable
fun Logo(url: String?, name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Palette.PanelHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = Palette.TextDim, fontSize = (size.value / 3).sp, fontWeight = FontWeight.Bold)
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
        }
    }
}

/** A film or series a poster is of, to ask TMDB for its poster when the provider's is missing or broken. */
class PosterSearch(val series: Boolean, val name: String, val tmdb: String? = null, val year: Int? = null)

/** Finds a poster on TMDB (set by the app: [com.lumentv.ui.MainActivity]); null without TMDB. */
val LocalPosterLookup = androidx.compose.runtime.staticCompositionLocalOf<(suspend (PosterSearch) -> String?)?> { null }

/**
 * A poster: the provider's picture, else (none, or it does not load: dead server, image taken off TMDB) TMDB's
 * poster of the title when [search] says which title it is, else the title in text.
 */
@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier, search: PosterSearch? = null) {
    var failed by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(url.isNullOrBlank()) }
    val lookup = LocalPosterLookup.current
    val alt by androidx.compose.runtime.produceState<String?>(null, failed, search?.name) {
        if (failed && search != null && lookup != null) value = runCatching { lookup(search) }.getOrNull()
    }
    val shown = if (failed) alt else url
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
        Text(title, color = Palette.TextDim, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        if (!shown.isNullOrBlank()) {
            AsyncImage(model = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { if (!failed) failed = true })
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun Badge(text: String, color: Color = Palette.Accent) {
    Text(
        text, color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SettingRow(title: String, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    // Phone: the value goes under the title (side by side, a long value would squeeze the title).
    if (com.lumentv.ui.LocalPhone.current) return FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = Palette.TextDim)
        }
    }
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.01f) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)) }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
fun Scrim(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Palette.Scrim), content = content)
}

fun initials(name: String): String =
    name.split(' ', '-', '|', ':').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

val columnGap = Arrangement.spacedBy(8.dp)
