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
    val Background = Color(0xFF0B0D12)
    val Panel = Color(0xFF141822)
    val PanelHigh = Color(0xFF1C2230)
    val Focus = Color(0xFFF2F4F8)
    val OnFocus = Color(0xFF0B0D12)
    val Accent = Color(0xFF4F8CFF)
    val AccentSoft = Color(0x334F8CFF)
    val Text = Color(0xFFE8EAF0)
    val TextDim = Color(0xFF9AA3B5)
    val Danger = Color(0xFFFF6B6B)
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
