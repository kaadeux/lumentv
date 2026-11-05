package com.lumentv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumentv.AppCore
import com.lumentv.core.NetReport
import com.lumentv.core.NetworkCheck

/** "État du réseau": internet and the provider's server, timed, then the likely cause (shared NetworkCheck). */
@Composable
internal fun NetworkDialog(core: AppCore, onDismiss: () -> Unit) {
    var run by remember { mutableStateOf(0) }
    val report by produceState<NetReport?>(null, run) { value = null; value = NetworkCheck.run(core) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.Panel,
        title = { Text("État du réseau") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = report
                if (r == null) Text("Test en cours…", color = Palette.TextDim)
                else {
                    r.checks.forEach { c ->
                        Row {
                            Text("●", color = when (c.level) { 0 -> Palette.Good; 1 -> Palette.Gold; else -> Palette.Danger }, modifier = Modifier.width(22.dp))
                            Text(c.what, color = Palette.TextDim, modifier = Modifier.width(110.dp))
                            Text(c.result)
                        }
                    }
                    Text(r.diagnosis ?: NetworkCheck.ALL_GOOD, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Palette.PanelHigh).padding(12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = { TextButton(onClick = { run++ }) { Text("Relancer le test") } },
    )
}
