@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.lumentv.ui.theme.Palette

data class MenuOption(val label: String, val checked: Boolean = false, val action: () -> Unit)

/**
 * For the root of a dialog: a long press on OK opens it while the key is still held, and that key's repeats and its
 * release reach the dialog and "click" the focused choice. OK only counts once it has been pressed again in here.
 */
@Composable
fun Modifier.ignoreHeldOk(): Modifier {
    var armed by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (!ok || armed) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { armed = true; false } else true
    }
}

/** A list of choices in a centred panel. Back closes it. */
@Composable
fun OptionsDialog(title: String, options: List<MenuOption>, onDismiss: () -> Unit, subtitle: String? = null) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        Column(
            Modifier.ignoreHeldOk().panelWidth(460.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(options) { i, o ->
                    FocusSurface(
                        onClick = { o.action(); onDismiss() },
                        modifier = Modifier.fillMaxWidth().then(if (i == options.indexOfFirst { it.checked }.coerceAtLeast(0)) Modifier.focusRequester(first) else Modifier),
                        container = Palette.PanelHigh, focusedScale = 1.02f,
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(o.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (o.checked) Text("✓", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val focus = remember { FocusRequester() }
        Column(Modifier.ignoreHeldOk().panelWidth(480.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Annuler", onDismiss, Modifier.focusRequester(focus))
                TvButton(confirm, { onConfirm(); onDismiss() }, primary = true)
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/** Text entry that suits a remote: the field looks like a button; OK opens the on-screen keyboard. */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Uri,
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    val display = remember { FocusRequester() }
    // Closing the keyboard swaps the editor for the display: give the focus back to it, not to the menu.
    LaunchedEffect(editing) {
        if (editing) wasEditing = true
        else if (wasEditing) { kotlinx.coroutines.delay(50); runCatching { display.requestFocus() } }
    }
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        if (!editing) {
            FocusSurface(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().focusRequester(display), focusedScale = 1.01f, container = Palette.PanelHigh) {
                Text(
                    when { value.isEmpty() -> placeholder; password -> "•".repeat(value.length.coerceAtMost(16)); else -> value },
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        } else {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 17.sp),
                cursorBrush = SolidColor(Palette.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { editing = false; keyboard?.hide(); onDone() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Focus.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyUp && (it.key == Key.Back || it.key == Key.Escape)) { editing = false; true }
                        else if (it.type == KeyEventType.KeyDown && (it.key == Key.DirectionDown || it.key == Key.DirectionUp)) { editing = false; keyboard?.hide(); false }
                        else false
                    },
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; keyboard?.show() }
        }
    }
}

/** A compact search field for filtering the list under it. */
@Composable
fun FilterField(value: String, placeholder: String = "Rechercher dans cette liste", onChange: (String) -> Unit) {
    TvTextField("", value, onChange, Modifier.fillMaxWidth().padding(bottom = 8.dp), placeholder = "🔍  $placeholder",
        keyboardType = KeyboardType.Text)
}

/** PIN pad ([length] digits), usable with the number keys of the remote or the on-screen digits. */
@Composable
fun PinDialog(title: String, onPin: (String) -> Unit, onDismiss: () -> Unit, error: String? = null, length: Int = 4, hint: String? = null) {
    Dialog(onDismissRequest = onDismiss) {
        var pin by remember { mutableStateOf("") }
        val first = remember { FocusRequester() }
        fun add(d: Char) {
            if (pin.length < length) pin += d
            if (pin.length == length) { onPin(pin); pin = "" }
        }
        Column(
            Modifier.panelWidth(360.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(24.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = when (e.key) {
                        Key.Zero, Key.NumPad0 -> '0'; Key.One, Key.NumPad1 -> '1'; Key.Two, Key.NumPad2 -> '2'
                        Key.Three, Key.NumPad3 -> '3'; Key.Four, Key.NumPad4 -> '4'; Key.Five, Key.NumPad5 -> '5'
                        Key.Six, Key.NumPad6 -> '6'; Key.Seven, Key.NumPad7 -> '7'; Key.Eight, Key.NumPad8 -> '8'
                        Key.Nine, Key.NumPad9 -> '9'; else -> null
                    }
                    if (digit != null) { add(digit); true } else false
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            Text("●".repeat(pin.length) + "○".repeat(length - pin.length), style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
            if (error != null) Text(error, color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            val rows = listOf("123", "456", "789", "←0✓")
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEachIndexed { c, ch ->
                        FocusSurface(
                            onClick = {
                                when (ch) { '←' -> pin = pin.dropLast(1); '✓' -> if (pin.isNotEmpty()) { onPin(pin); pin = "" }; else -> add(ch) }
                            },
                            modifier = Modifier.size(64.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                            container = Palette.PanelHigh,
                        ) { Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Text(ch.toString(), style = MaterialTheme.typography.headlineSmall) } }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}

/** Opens or creates the online profile: a name and a passphrase (at least 10 characters). */
@Composable
fun ProfileDialog(busy: Boolean, error: String?, onSubmit: (String, String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        var name by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        Column(Modifier.panelWidth(560.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Profil en ligne", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Text("Le même nom et la même phrase secrète sur la TV et le PC partagent playlists, favoris, historique et réglages. " +
                "Tout est chiffré sur l'appareil : sans la phrase secrète, personne ne peut lire le profil, pas même le serveur. " +
                "Une phrase oubliée ne peut pas être récupérée.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            TvTextField("Nom du profil", name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus), placeholder = "Par exemple : famille-martin",
                keyboardType = KeyboardType.Text)
            TvTextField("Phrase secrète (10 caractères minimum)", pass, { pass = it }, Modifier.fillMaxWidth(), password = true,
                keyboardType = KeyboardType.Password)
            if (error != null) Text(error, color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
            // The main button spans the width, so Down from the fields always lands on it; Back cancels.
            TvButton(if (busy) "Connexion…" else "Se connecter / créer le profil", { if (!busy) onSubmit(name, pass) }, Modifier.fillMaxWidth(), primary = true)
            Text("Retour : annuler", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
