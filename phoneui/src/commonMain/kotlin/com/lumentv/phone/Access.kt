package com.lumentv.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
internal fun AccessScreen(gate: AccessGate) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(32.dp).imePadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("lumenTV", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Text("Application réservée à la famille.\nEntre le code d'accès à 6 chiffres.", color = Palette.TextDim, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
        OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6); error = null }, label = { Text("Code d'accès") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = Palette.Danger, modifier = Modifier.padding(top = 8.dp)) }
        PhoneButton("Valider", primary = true, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            if (!gate.unlock(code)) { error = "Code incorrect."; code = "" }
        }
    }
}
