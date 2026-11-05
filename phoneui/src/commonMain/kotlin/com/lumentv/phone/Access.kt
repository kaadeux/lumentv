package com.lumentv.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumentv.core.Crypto
import com.lumentv.core.KeyValues

/**
 * The family access code, asked once per iPhone, as on Android: only its hash is in the app (Info.plist, from the
 * repository's secret at build time); an app built without one has no gate. A new code locks every device again.
 */
class AccessGate(private val hash: String, private val store: KeyValues) {
    var unlocked by mutableStateOf(hash.isEmpty() || store.string("unlocked") == hash)
        private set

    /** True when [code] is right; the iPhone then stays unlocked. */
    fun unlock(code: String): Boolean {
        if (hash(code) != hash) return false
        store.put("unlocked", hash)
        unlocked = true
        return true
    }

    companion object {
        /** The same hash as Android's (Access.hash). */
        fun hash(code: String): String =
            Crypto.sha256("lumen-access:${code.trim()}".encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/** The family code's screen, shared with the PC (uikit). */
@Composable
internal fun AccessScreen(gate: AccessGate) = com.lumentv.ui.kit.AccessGateScreen(gate::unlock) {}
