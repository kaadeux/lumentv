package com.lumentv.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        if (com.lumentv.core.AccessCode.hash(code) != hash) return false
        store.put("unlocked", hash)
        unlocked = true
        return true
    }
}

/** The family code's screen, shared with the PC (uikit). */
@Composable
internal fun AccessScreen(gate: AccessGate) = com.lumentv.ui.kit.AccessGateScreen(gate::unlock) {}
