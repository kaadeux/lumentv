package com.lumentv.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.lumentv.AppCore
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.settings.Keys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * The parental control, as on Android: groups locked by the PIN (asked once per group until the app is closed),
 * whatever the way to a channel is (TV, search, favourites, guide, sport).
 */
internal object Parental {
    class Ask(val title: String, val result: CompletableDeferred<Boolean>)

    /** The PIN asked now, shown by [PinHost]. */
    var ask by mutableStateOf<Ask?>(null)
    /** A short message (no PIN yet…), shown by [PinHost]. */
    var notice by mutableStateOf<String?>(null)
    private val unlocked = mutableStateMapOf<String, Boolean>()

    private fun key(pid: Long, group: String) = "$pid\u0001$group"
    fun isUnlocked(pid: Long, group: String) = unlocked[key(pid, group)] == true
    fun relock(pid: Long, group: String) { unlocked.remove(key(pid, group)) }

    /** Asks the PIN; true when it was right (or there is none). */
    suspend fun check(core: AppCore, title: String): Boolean {
        if (core.settings.current().pinHash.isEmpty()) return true
        val a = Ask(title, CompletableDeferred())
        ask = a
        return a.result.await()
    }

    suspend fun unlockGroup(core: AppCore, pid: Long, group: String): Boolean {
        if (isUnlocked(pid, group)) return true
        val ok = check(core, "Groupe verrouillé « $group »")
        if (ok) unlocked[key(pid, group)] = true
        return ok
    }

    /** False when the channel's group is locked and the PIN was not given. */
    suspend fun allow(core: AppCore, c: ChannelRow): Boolean {
        val pid = c.ch.playlistId
        if (isUnlocked(pid, c.ch.groupName) || c.ch.groupName !in core.library.lockedGroups(pid)) return true
        return unlockGroup(core, pid, c.ch.groupName)
    }
}

/** Where the PIN is typed: four digits or more. */
@Composable
internal fun PinDialog(title: String, subtitle: String?, error: String?, onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember(title) { mutableStateOf("") }
    com.lumentv.ui.kit.KitDialog(title, onDismiss, subtitle, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.lumentv.ui.kit.KitButton("Annuler", modifier = Modifier.weight(1f), onClick = onDismiss)
            com.lumentv.ui.kit.KitButton("Valider", primary = true, modifier = Modifier.weight(1f)) { if (pin.length >= 4) { onPin(pin); pin = "" } }
        }
    }) {
        OutlinedTextField(pin, { v -> pin = v.filter { it.isDigit() }.take(8) }, label = { Text("Code PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = Palette.Danger) }
    }
}

/** The PIN asked by [Parental.check] and its notices, over every page. */
@Composable
internal fun PinHost(core: AppCore) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Parental.ask?.let { a ->
        PinDialog(a.title, "Saisis le code PIN pour continuer.", error,
            onDismiss = { error = null; Parental.ask = null; a.result.complete(false) }) { pin ->
            scope.launch {
                if (core.settings.checkPin(pin)) { error = null; Parental.ask = null; a.result.complete(true) }
                else error = "Code incorrect."
            }
        }
    }
    Parental.notice?.let { n ->
        com.lumentv.ui.kit.KitDialog(n, onDismiss = { Parental.notice = null },
            buttons = { com.lumentv.ui.kit.KitButton("OK", primary = true, modifier = Modifier.fillMaxWidth()) { Parental.notice = null } })
    }
}

/** Paramètres › Contrôle parental: the PIN (set, change, remove) and the hidden items. */
@Composable
internal fun ParentalSection(core: AppCore, hint: String = SettingsExtras().parentalHint) {
    val settings by core.settings.flow.collectAsState(null)
    val s = settings ?: return
    val hasPin = s.pinHash.isNotEmpty()
    // "current" → "new" → "confirm" to set or change it, "remove" to take it away.
    var step by remember { mutableStateOf<String?>(null) }
    var first by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Section("Contrôle parental") {
        Text(hint, color = Palette.TextDim, fontSize = 14.sp)
        Toggle("Afficher les éléments masqués", "Pour les retrouver et les afficher à nouveau.", s.showHidden) { on ->
            scope.launch {
                if (!on || Parental.check(core, "Afficher les éléments masqués")) core.settings.set(Keys.showHidden, on)
            }
        }
        Row2(if (hasPin) "Changer le code PIN" else "Définir un code PIN") { step = if (hasPin) "current" else "new"; error = null }
        if (hasPin) Row2("Supprimer le code PIN") { step = "remove"; error = null }
    }
    step?.let { st ->
        PinDialog(when (st) {
            "current", "remove" -> "Code PIN actuel"
            "new" -> "Nouveau code PIN"
            else -> "Confirme le code PIN"
        }, if (st == "new") "Quatre chiffres ou plus." else null, error, onDismiss = { step = null; first = ""; error = null }) { pin ->
            scope.launch {
                when (st) {
                    "current" -> if (core.settings.checkPin(pin)) { step = "new"; error = null } else error = "Code incorrect."
                    "new" -> { first = pin; step = "confirm"; error = null }
                    "confirm" -> if (pin == first) {
                        core.settings.setPin(pin); step = null; first = ""; Parental.notice = "Code PIN enregistré."
                    } else { step = "new"; first = ""; error = "Les deux codes ne sont pas les mêmes." }
                    "remove" -> if (core.settings.checkPin(pin)) {
                        core.settings.setPin(""); step = null; Parental.notice = "Code PIN supprimé. Les groupes verrouillés s'ouvrent sans code."
                    } else error = "Code incorrect."
                }
            }
        }
    }
}

@Composable
private fun Row2(label: String, onClick: () -> Unit) = PhoneButton(label, modifier = Modifier.fillMaxWidth(), onClick = onClick)
