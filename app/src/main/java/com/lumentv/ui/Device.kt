package com.lumentv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One app for the TV and the phone: the same screens and data, laid out for a remote (TV) or for the fingers
 * (phone). Screens read [LocalPhone] to pick their layout.
 */
val LocalPhone = staticCompositionLocalOf { false }

object Device {
    /** A TV (Android TV / Google TV box or set): remote, landscape, 10-foot layout. Everything else is a phone/tablet. */
    fun isTv(context: Context): Boolean {
        // Tests: `adb shell setprop debug.lumentv.phone 1` shows the phone layout on a TV box (lost on restart).
        if (prop("debug.lumentv.phone") == "1") return false
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /** Tests: `adb shell setprop debug.lumentv.width 400` squeezes the phone layout to 400 dp, the width of an upright phone. */
    fun testWidthDp(): Int = prop("debug.lumentv.width")?.toIntOrNull() ?: 0

    private fun prop(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}
