package com.lumentv.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumentv.ui.theme.Palette

/**
 * The "liquid glass" look of the bars: a dark, slightly see-through pill lit from above (a light gradient and a thin
 * bright edge on top), over the picture behind. Android TV boxes cannot blur what is behind cheaply, so the depth comes
 * from the gradients and the edge.
 */
object Glass {
    val Fill = Brush.verticalGradient(listOf(Color(0xB32A2A31), Color(0xA6151519)))
    val Edge = Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x14FFFFFF), Color(0x0AFFFFFF)))
    /** The moving highlight behind the focused or chosen element. */
    val Lens = Brush.verticalGradient(listOf(Color(0x4DFFFFFF), Color(0x1FFFFFFF)))
    val LensEdge = Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1AFFFFFF)))
    /** The chosen element when the focus is elsewhere: quieter. */
    val Rest = Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color(0x0FFFFFFF)))

    val Bouncy = spring<Dp>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
}

/** A glass pill around a bar. */
fun Modifier.glassPill(shape: Shape = RoundedCornerShape(32.dp)): Modifier =
    this.clip(shape).background(Glass.Fill).border(1.dp, Glass.Edge, shape)

/**
 * One element of a glass bar (a tab, a group, a season, an icon): white text on the glass, a light lens when focused
 * (it grows a little, with a spring), a quieter one and an accent dot when it is the chosen one.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassChip(
    onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, shape: Shape = RoundedCornerShape(26.dp),
    dot: Boolean = selected, ownLens: Boolean = true, onFocus: (Boolean) -> Unit = {}, onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "chip")
    val lens by animateFloatAsState(if (!ownLens) 0f else if (focused) 1f else if (selected) 0.55f else 0f, tween(220), label = "lens")
    val text by animateColorAsState(if (focused || selected) Color.White else Palette.TextDim, tween(200), label = "text")
    Box(
        modifier.scale(scale).onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, onLongClick = onLongClick, onClick = onClick)
            .clip(shape)
            .drawBehind {
                if (lens > 0f) {
                    drawRect(Glass.Lens, alpha = lens)
                    // The lit top edge of the lens.
                    drawRect(Glass.LensEdge, size = size.copy(height = 1.2f.dp.toPx()), alpha = lens)
                }
                if (focused) drawRect(Brush.radialGradient(listOf(Color(0x33FFFFFF), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width), alpha = lens)
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides text) { content(focused) }
        if (dot) Box(Modifier.align(Alignment.BottomCenter).offset(y = (-3).dp).size(4.dp).clip(CircleShape).background(Palette.Accent))
    }
}

/** The sliding lens of a row of tabs: follows the focused tab, or rests on the chosen one. */
@Composable
fun SlidingLens(x: Dp, width: Dp, active: Boolean, visible: Boolean) {
    val ax by animateDpAsState(x, Glass.Bouncy, label = "lensX")
    val aw by animateDpAsState(width, Glass.Bouncy, label = "lensW")
    val alpha by animateFloatAsState(if (!visible) 0f else if (active) 1f else 0.6f, tween(220), label = "lensA")
    if (alpha > 0f) Box(
        Modifier.offset(x = ax).size(aw, 40.dp).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(24.dp))
            .background(if (active) Glass.Lens else Glass.Rest)
            .border(1.dp, Glass.LensEdge, RoundedCornerShape(24.dp)),
    )
}
