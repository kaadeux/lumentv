package com.lumentv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object Palette {
    // V2: neutral dark greys (a streaming service's), a warm accent.
    val Background = Color(0xFF0E0E10)
    val Panel = Color(0xFF19191C)
    val PanelHigh = Color(0xFF26262A)
    val Focus = Color(0xFFF2F2F2)
    val OnFocus = Color(0xFF0E0E10)
    val Accent = Color(0xFFFF7A2F)
    val AccentSoft = Color(0x33FF7A2F)
    val Text = Color(0xFFF2F2F2)
    val TextDim = Color(0xFFA0A0A8)
    val Danger = Color(0xFFFF5A5F)
    val Good = Color(0xFF4CD98A)
    val Gold = Color(0xFFFFC857)
    val Scrim = Color(0xCC000000)
}

private val LumenTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Color.White,
            background = Palette.Background,
            onBackground = Palette.Text,
            surface = Palette.Panel,
            onSurface = Palette.Text,
            surfaceVariant = Palette.PanelHigh,
            onSurfaceVariant = Palette.TextDim,
            inverseSurface = Palette.Focus,
            inverseOnSurface = Palette.OnFocus,
            error = Palette.Danger,
        ),
        typography = LumenTypography,
        content = content,
    )
}
