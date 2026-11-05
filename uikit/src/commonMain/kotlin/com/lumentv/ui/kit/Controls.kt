package com.lumentv.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
