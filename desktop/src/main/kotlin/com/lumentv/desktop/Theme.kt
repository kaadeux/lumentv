package com.lumentv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import java.security.MessageDigest
import kotlinx.coroutines.launch
import androidx.compose.ui.node.invalidateDraw
import java.util.prefs.Preferences

/** Same colours as the TV app. */
object Pal {
    val bg = Color(0xFF0E1117)
    val side = Color(0xFF141821)
    val panel = Color(0xFF181C25)
    val panelHigh = Color(0xFF242933)
    val accent = Color(0xFF4F8CFF)
    val text = Color(0xFFE6E8EE)
    val dim = Color(0xFF9AA1AE)
    val danger = Color(0xFFFF6B6B)
}

/** Hover / press feedback: a light veil (the default one darkens the dark rows). */
private object SoftIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7

    private class Node(private val source: androidx.compose.foundation.interaction.InteractionSource) : Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
        private var hovered = false
        private var pressed = false
        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect {
                    when (it) {
                        is androidx.compose.foundation.interaction.HoverInteraction.Enter -> hovered = true
                        is androidx.compose.foundation.interaction.HoverInteraction.Exit -> hovered = false
                        is androidx.compose.foundation.interaction.PressInteraction.Press -> pressed = true
                        is androidx.compose.foundation.interaction.PressInteraction.Release, is androidx.compose.foundation.interaction.PressInteraction.Cancel -> pressed = false
                    }
                    invalidateDraw()
                }
            }
        }
        override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
            drawContent()
            if (hovered || pressed) drawRect(Color.White.copy(alpha = if (pressed) 0.10f else 0.05f))
        }
    }
}

/** The PC's colours for the pieces shared with the TV, the phone and the iPhone (:uikit). */
val KitPalette = com.lumentv.ui.kit.KitColors(
    panel = Pal.panel, panelHigh = Pal.panelHigh, text = Pal.text, dim = Pal.dim,
    accent = Pal.accent, accentSoft = Pal.accent.copy(alpha = 0.2f), danger = Pal.danger,
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Pal.accent, onPrimary = Color.White, primaryContainer = Pal.accent, onPrimaryContainer = Color.White,
        secondaryContainer = Pal.accent.copy(alpha = 0.3f), onSecondaryContainer = Pal.text, outline = Pal.panelHigh, background = Pal.bg, surface = Pal.panel, onSurface = Pal.text, onBackground = Pal.text,
        surfaceVariant = Pal.panelHigh, onSurfaceVariant = Pal.dim, error = Pal.danger),
) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides SoftIndication, com.lumentv.ui.kit.LocalKitColors provides KitPalette) {
        Box(Modifier.fillMaxSize().background(Pal.bg)) { content() }
    }
}

/** Family access code, as on the TV: asked once per Windows account, only its hash is in the app. */
object Access {
    private val prefs = Preferences.userRoot().node("lumentv")
    private fun hash(code: String) = MessageDigest.getInstance("SHA-256").digest("lumen-access:${code.trim()}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun isUnlocked() = BuildInfo.ACCESS_HASH.isEmpty() || prefs.get("unlocked", "") == BuildInfo.ACCESS_HASH
    fun unlock(code: String): Boolean {
        if (hash(code) != BuildInfo.ACCESS_HASH) return false
        prefs.put("unlocked", BuildInfo.ACCESS_HASH); return true
    }
}

@Composable
fun AccessScreen(onUnlocked: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(Pal.panel).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Lumen TV", style = MaterialTheme.typography.headlineSmall, color = Pal.text)
            Text("Application réservée à la famille. Entrez le code d'accès à 6 chiffres.", color = Pal.dim)
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6); error = false }, singleLine = true, label = { Text("Code d'accès") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            if (error) Text("Code incorrect", color = Pal.danger)
            Spacer(Modifier.height(4.dp))
            Button({ if (Access.unlock(code)) onUnlocked() else error = true }, enabled = code.length == 6) { Text("Valider") }
        }
    }
}
