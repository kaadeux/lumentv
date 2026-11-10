package com.lumentv.ui.kit

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = Color(0xFFF2F2F2)

/**
 * Something to press, with a ring when it has the focus (the TV's remote, the PC's keyboard): on a phone it is the
 * same as a plain clickable.
 */
@Composable
fun Modifier.pressable(shape: Shape, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return this.scale(if (focused) 1.04f else 1f).clip(shape)
        .then(if (focused) Modifier.border(2.dp, Light, shape) else Modifier)
        .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
}

/** A button: white for the main action ([primary]), translucent for the others. */
@Composable
fun KitButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalKitColors.current
    Box(modifier.height(46.dp).pressable(RoundedCornerShape(10.dp), onClick).background(if (primary) Light else Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (primary) Color(0xFF0E0E10) else c.text, maxLines = 1,
            softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** A chip to pick, white when chosen. */
@Composable
fun KitChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val c = LocalKitColors.current
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.height(36.dp).pressable(shape, onClick).background(if (selected) Color(0xE6F2F2F2) else Color(0x26FFFFFF))
        .border(1.dp, if (selected) Color.Transparent else Color(0x1FFFFFFF), shape)
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color(0xFF0E0E10) else c.text, maxLines = 1)
    }
}

/** A setting on or off, with what it does; the whole row toggles it. */
@Composable
fun KitToggle(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalKitColors.current
    Row(Modifier.pressable(RoundedCornerShape(10.dp)) { onChange(!on) }.padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = c.dim, fontSize = 13.sp)
        }
        Switch(checked = on, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
}

/**
 * The loading of a list, the same on every app: "Chargement des films · 12 000" over a thin bar. [fraction] null: a
 * step whose length is not known (the guide), the bar then fills slowly back and forth.
 */
@Composable
fun KitLoadingBar(label: String, fraction: Float?, modifier: Modifier = Modifier) {
    val c = LocalKitColors.current
    val shown by androidx.compose.animation.core.animateFloatAsState(fraction ?: 0f, label = "load")
    val pulse = if (fraction == null) androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse").animateFloat(
        0.1f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse").value else null
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.panelHigh).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
            if (fraction != null) Text("${(fraction * 100).toInt()} %", fontSize = 13.sp, color = c.dim, maxLines = 1)
        }
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(pulse ?: shown).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        }
    }
}

/** A small pill on a poster ("Nouvelle saison", "Nouvel épisode"), the same on every app. */
@Composable
fun KitBadge(text: String, modifier: Modifier = Modifier) {
    val c = LocalKitColors.current
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(c.accent).padding(horizontal = 6.dp, vertical = 2.dp))
}
