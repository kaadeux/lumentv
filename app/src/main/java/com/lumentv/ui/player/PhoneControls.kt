@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.ui.screens.clock
import com.lumentv.ui.theme.Palette

/** What the phone controls act on; PlayerScreen wires each to the same code as the remote's keys. */
internal class PhoneActions(
    val back: () -> Unit,
    val playPause: () -> Unit,
    val seekBy: (Long) -> Unit,
    val seekTo: (Long) -> Unit,
    val options: () -> Unit,
    val subtitles: () -> Unit,
    val channels: () -> Unit,
    val zap: (Int) -> Unit,
    val next: (() -> Unit)?,
    /** Any touch on the controls: keeps them on screen a little longer. */
    val touched: () -> Unit,
)

private val topScrim = Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
private val bottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))

/**
 * The player's controls on a phone, over the picture: title and buttons at the top, big play / seek (or channel)
 * buttons in the middle, the position bar (or the programme) at the bottom. A tap on the picture shows / hides them.
 */
@Composable
internal fun PhoneControls(
    title: String, subtitle: String?, live: Boolean, playing: Boolean, position: Long, duration: Long, seekable: Boolean,
    programme: @Composable () -> Unit, a: PhoneActions,
) {
    Box(Modifier.fillMaxSize()) {
        // Top: back, title, subtitles, options
        Row(Modifier.fillMaxWidth().background(topScrim).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Round("←", 48.dp, "Retour") { a.back() }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Pill("CC") { a.touched(); a.subtitles() }
            Spacer(Modifier.width(8.dp))
            Pill("⚙ Options") { a.touched(); a.options() }
        }

        // Middle: the big buttons
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                Round("▲", 60.dp, "Chaîne précédente") { a.touched(); a.zap(-1) }
                Round(if (playing) "❚❚" else "▶", 76.dp, "Lecture / pause", primary = true) { a.touched(); a.playPause() }
                Round("▼", 60.dp, "Chaîne suivante") { a.touched(); a.zap(1) }
            } else {
                Round("−10", 60.dp, "Reculer de 10 secondes") { a.touched(); a.seekBy(-10_000) }
                Round(if (playing) "❚❚" else "▶", 76.dp, "Lecture / pause", primary = true) { a.touched(); a.playPause() }
                Round("+30", 60.dp, "Avancer de 30 secondes") { a.touched(); a.seekBy(30_000) }
            }
        }

        // Bottom: position bar (films, episodes) or programme (live), and the list / next buttons
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(bottomScrim).padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 14.dp)) {
            if (live) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { programme() }
                    Spacer(Modifier.width(12.dp))
                    Pill("☰ Chaînes") { a.touched(); a.channels() }
                }
            } else {
                if (seekable && duration > 0) SeekBar(position, duration, a)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(position), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    a.next?.let { n -> Pill("⏭ Épisode suivant") { a.touched(); n() }; Spacer(Modifier.width(12.dp)) }
                    if (duration > 0) Text("−" + clock(duration - position) + " / " + clock(duration), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
        }
    }
}

/** A position bar the finger can tap or drag; the time follows the finger and the jump happens on release. */
@Composable
private fun SeekBar(position: Long, duration: Long, a: PhoneActions) {
    var width by remember { mutableFloatStateOf(1f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag ?: (position.toFloat() / duration).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(36.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(duration) {
                detectTapGestures { o -> a.touched(); a.seekTo((o.x / width).coerceIn(0f, 1f).times(duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / width).coerceIn(0f, 1f); a.touched() },
                    onDragEnd = { drag?.let { a.seekTo((it * duration).toLong()) }; drag = null },
                    onDragCancel = { drag = null },
                    onHorizontalDrag = { change, _ -> drag = (change.position.x / width).coerceIn(0f, 1f); a.touched() },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF)))
        Box(Modifier.fillMaxWidth(shown).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
        // The knob, and the time under the finger while dragging
        val knob = with(androidx.compose.ui.platform.LocalDensity.current) { (shown * width).toDp() }
        Box(Modifier.offset(x = knob - 8.dp).size(16.dp).clip(CircleShape).background(Color.White))
        drag?.let { f ->
            Text(clock((f * duration).toLong()), style = MaterialTheme.typography.labelLarge, color = Color.White,
                modifier = Modifier.offset(x = (knob - 24.dp).coerceAtLeast(0.dp), y = (-26).dp).clip(RoundedCornerShape(8.dp))
                    .background(Palette.Scrim).padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun Round(label: String, size: Dp, description: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (primary) Color.White else Color(0x66000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = if (size > 56.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            color = if (primary) Palette.OnFocus else Color.White)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x99141822)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp))
}

/** The whole picture as a touch surface: tap = show / hide the controls, double tap left / right = −10 s / +10 s. */
internal fun Modifier.phoneGestures(seekable: Boolean, onTap: () -> Unit, onSeek: (Long) -> Unit): Modifier = pointerInput(seekable) {
    detectTapGestures(
        onTap = { onTap() },
        onDoubleTap = if (seekable) ({ o -> onSeek(if (o.x < size.width / 2) -10_000L else 10_000L) }) else null,
    )
}
