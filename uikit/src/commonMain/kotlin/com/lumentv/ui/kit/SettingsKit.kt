package com.lumentv.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colours of the app using the kit (each app has its own palette). */
data class KitColors(
    val panel: Color, val panelHigh: Color, val text: Color, val dim: Color,
    val accent: Color, val accentSoft: Color, val danger: Color,
)

/** Lumen TV's TV and phone colours; the PC gives its own. */
val LocalKitColors = staticCompositionLocalOf {
    KitColors(
        panel = Color(0xFF19191C), panelHigh = Color(0xFF26262A), text = Color(0xFFF2F2F2), dim = Color(0xFFA0A0A8),
        accent = Color(0xFFFF7A2F), accentSoft = Color(0x33FF7A2F), danger = Color(0xFFFF5A5F),
    )
}

/** A settings section's title, small and in capitals above its card (as iOS's settings do). */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) = Text(
    title.uppercase(), color = LocalKitColors.current.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
    modifier = modifier.padding(start = 6.dp, bottom = 8.dp),
)

/** The card a section's settings sit in. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalKitColors.current.panel).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) { content() }

/** A titled section: [SectionTitle] then [SectionCard]. */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) = Column(modifier.fillMaxWidth()) {
    SectionTitle(title)
    SectionCard(content = content)
}

/** Big numbers side by side: "13414 chaînes · 23624 films · 6198 séries". */
@Composable
fun StatTiles(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) = Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    val c = LocalKitColors.current
    stats.forEach { (value, label) ->
        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.panelHigh).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, color = c.dim, fontSize = 12.sp)
        }
    }
}

/** A small label in the accent colour: "Active". */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) = Text(
    text, color = LocalKitColors.current.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    modifier = modifier.clip(RoundedCornerShape(8.dp)).background(LocalKitColors.current.accentSoft).padding(horizontal = 8.dp, vertical = 3.dp),
)

/** Whether a list's login is encrypted on the device ([inClear]: the key store refused). */
@Composable
fun SecretsLine(inClear: Boolean, where: String = "") = Text(
    if (inClear) "⚠️  Identifiants non chiffrés" else "🔒  Identifiants chiffrés$where",
    color = if (inClear) LocalKitColors.current.danger else LocalKitColors.current.dim, fontSize = 13.sp,
)

/** The privacy summary: its state with a shield (⚠️ when partial), then one line per point. */
@Composable
fun PrivacyLines(title: String, lines: List<String>, partial: Boolean, note: String? = null) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val c = LocalKitColors.current
    Text((if (partial) "⚠️  " else "🛡️  ") + title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    lines.forEach { Text("•  $it", color = c.dim, fontSize = 14.sp) }
    note?.let { Text(it, color = c.dim, fontSize = 13.sp) }
}

/** A list in Mes listes: its name, what it is, the secrets line, and "Active" or ⋮ on the right. */
@Composable
fun ListTile(name: String, kind: String, active: Boolean, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) = Row(
    modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LocalKitColors.current.panelHigh).padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    val c = LocalKitColors.current
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(kind, color = c.dim, fontSize = 13.sp, maxLines = 2)
        extra()
    }
    if (active) Badge("Active") else Text("⋮", color = c.dim, fontSize = 18.sp)
}
