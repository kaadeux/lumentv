@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.lumentv.ui

import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.delay

/** The full-screen picture behind a screen (films, series, their pages); set by [ScreenBackdrop]. */
object Backdrop {
    var url by mutableStateOf<String?>(null)
}

/** Shows [url] full screen behind this screen while it is on, faded into the background on the left and at the bottom. */
@Composable
fun ScreenBackdrop(url: String?) {
    DisposableEffect(url) {
        Backdrop.url = url
        onDispose { if (Backdrop.url == url) Backdrop.url = null }
    }
}

@Composable
fun BackdropLayer() {
    Crossfade(Backdrop.url, animationSpec = tween(450), label = "backdrop") { url ->
        if (url == null) return@Crossfade
        Box(Modifier.fillMaxSize()) {
            // On the right two thirds, as on a streaming service: the text sits on the dark left part.
            AsyncImage(url, null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd,
                modifier = Modifier.fillMaxSize().padding(start = 220.dp))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.18f to Palette.Background, 0.55f to Palette.Background.copy(alpha = 0.55f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.Background.copy(alpha = 0.55f), 0.18f to Color.Transparent, 0.55f to Color.Transparent, 0.78f to Palette.Background)))
        }
    }
}

class TopTab(val screen: Screen, val label: String)
class TopIcon(val screen: Screen?, val label: String, val icon: ImageVector, val onClick: (() -> Unit)? = null)

/**
 * The bar on top of every screen of the TV: the logo, the main tabs in a pill (TV, Guide, Sports, Films, Séries), the
 * other screens as round icons, and the time. Down enters the screen; entering the bar lands on the current tab.
 */
@Composable
fun TopNav(tabs: List<TopTab>, icons: List<TopIcon>, selected: Screen, first: FocusRequester, onSelect: (Screen) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("lumen", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text("TV", fontSize = 24.sp, fontWeight = FontWeight.Light, color = Palette.Text)
            Box(Modifier.padding(start = 2.dp, bottom = 6.dp).size(6.dp).clip(CircleShape).background(Palette.Accent))
        }
        Spacer(Modifier.weight(1f))
        val current = tabs.firstOrNull { it.screen == selected }
        val last = remember { FocusRequester() }
        val density = androidx.compose.ui.platform.LocalDensity.current
        // Where each tab is in the pill, for the lens that slides from tab to tab (spring) under the focus.
        val spots = remember { androidx.compose.runtime.mutableStateMapOf<Int, Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>>() }
        var focusedTab by remember { mutableStateOf(-1) }
        val target = if (focusedTab >= 0) focusedTab else tabs.indexOfFirst { it.screen == selected }
        Box(Modifier.glassPill().padding(4.dp), contentAlignment = Alignment.CenterStart) {
            spots[target]?.let { (x, w) -> SlidingLens(x, w, active = focusedTab >= 0, visible = true) }
            // Entering the tabs: from the icons on their right, the last tab (the nearest); from the screen below, the current one.
            Row(Modifier.focusProperties { enter = { dir -> if (dir == androidx.compose.ui.focus.FocusDirection.Left && tabs.last().screen != selected) last else first } }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { i, t ->
                    val on = t.screen == selected
                    GlassChip({ onSelect(t.screen) },
                        Modifier.then(if (on || (current == null && t === tabs.first())) Modifier.focusRequester(first) else Modifier)
                            .then(if (t === tabs.last() && !(on || (current == null && t === tabs.first()))) Modifier.focusRequester(last) else Modifier)
                            .height(40.dp)
                            .onGloballyPositioned { c -> with(density) { spots[i] = c.positionInParent().x.toDp() to c.size.width.toDp() } },
                        selected = on, ownLens = false, onFocus = { f -> if (f) focusedTab = i else if (focusedTab == i) focusedTab = -1 }) {
                        Text(t.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.glassPill().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            icons.forEach { i ->
                val on = i.screen != null && i.screen == selected
                GlassChip({ i.onClick?.invoke() ?: i.screen?.let(onSelect) }, Modifier.size(40.dp), selected = on, shape = CircleShape, dot = false) {
                    Icon(i.icon, i.label, Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        val time by produceState(clockNow()) { while (true) { delay(15_000); value = clockNow() } }
        Text(time, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
    }
}

private fun clockNow() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date())
