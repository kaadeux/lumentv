package com.lumentv.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** The system's back (Android's gesture or button) closes the page shown; nothing on the iPhone (its pages have ‹). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** A line the platform adds to "Plus" (Android: back to the classic interface). */
class PlatformEntry(val icon: String, val title: String, val subtitle: String, val onClick: () -> Unit)

val LocalPlatformEntries = staticCompositionLocalOf<List<PlatformEntry>> { emptyList() }
