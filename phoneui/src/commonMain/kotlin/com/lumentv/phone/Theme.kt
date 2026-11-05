package com.lumentv.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The colours of the TV and phone app (ui/theme/Theme.kt in :app), for the shared phone screens. */
object Palette {
    val Background = Color(0xFF0E0E10)
    val Panel = Color(0xFF19191C)
    val PanelHigh = Color(0xFF26262A)
    val Accent = Color(0xFFFF7A2F)
    val AccentSoft = Color(0x33FF7A2F)
    val Text = Color(0xFFF2F2F2)
    val TextDim = Color(0xFFA0A0A8)
    val Danger = Color(0xFFFF5A5F)
    val Gold = Color(0xFFFFC857)
    val Good = Color(0xFF4CD98A)
}

@Composable
fun LumenTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Palette.Accent, onPrimary = Color.White, background = Palette.Background, onBackground = Palette.Text,
        surface = Palette.Background, onSurface = Palette.Text, surfaceVariant = Palette.Panel, onSurfaceVariant = Palette.TextDim,
        surfaceContainer = Palette.Panel, secondaryContainer = Palette.AccentSoft, onSecondaryContainer = Palette.Text,
        error = Palette.Danger,
    ),
) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.Text, content = content)
}
