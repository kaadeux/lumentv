package com.lumentv.core

import android.content.Context
import com.lumentv.BuildConfig
import java.security.MessageDigest

/**
 * Family access code, asked once per device. Only its hash is built into the APK (from `lumen.accessCode` in
 * local.properties); a build without a code has no gate. Changing the code locks every device again.
 */
object Access {
    private const val PREFS = "access"

    fun hash(code: String): String = MessageDigest.getInstance("SHA-256")
        .digest("lumen-access:${code.trim()}".toByteArray()).joinToString("") { "%02x".format(it) }

    fun isUnlocked(context: Context): Boolean = BuildConfig.ACCESS_HASH.isEmpty() ||
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("unlocked", "") == BuildConfig.ACCESS_HASH

    /** @return true when [code] is right; the device then stays unlocked. */
    fun unlock(context: Context, code: String): Boolean {
        if (hash(code) != BuildConfig.ACCESS_HASH) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("unlocked", BuildConfig.ACCESS_HASH).apply()
        return true
    }
}
