package com.lumentv.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One line of "État du réseau": what, how it went, [level] 0 ok / 1 warning / 2 bad. */
class NetLine(val what: String, val result: String, val level: Int)

/** "État du réseau" on every app: the lines ([lines] null: the test is running), then the likely cause. */
@Composable
fun NetworkReportDialog(lines: List<NetLine>?, diagnosis: String?, onRerun: () -> Unit, onDismiss: () -> Unit, rootModifier: Modifier = Modifier) {
    val c = LocalKitColors.current
    val close = remember { FocusRequester() }
    KitDialog("État du réseau", onDismiss, rootModifier = rootModifier, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KitButton("Fermer", primary = true, modifier = Modifier.weight(1f).focusRequester(close), onClick = onDismiss)
            KitButton("Relancer le test", modifier = Modifier.weight(1f), onClick = onRerun)
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lines == null) Text("Test en cours…", color = c.dim)
            else {
                lines.forEach { l ->
                    Row {
                        Text("●", color = when (l.level) { 0 -> Color(0xFF4CD98A); 1 -> Color(0xFFFFC857); else -> c.danger }, modifier = Modifier.width(22.dp))
                        Text(l.what, color = c.dim, modifier = Modifier.width(120.dp))
                        Text(l.result, color = c.text, modifier = Modifier.weight(1f))
                    }
                }
                diagnosis?.let {
                    Text(it, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(c.panelHigh).padding(12.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}
