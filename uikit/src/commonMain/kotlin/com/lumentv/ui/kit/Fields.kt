package com.lumentv.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Typed exactly as written: no capital letter at the start, no correction, no smart quotes or dashes. */
private val exactKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password)

/**
 * A field typed exactly as written (server, user name, profile name), with "Coller" to take what was copied
 * (a password manager, a note); [secret] hides it behind dots, with "Afficher" to check what was typed.
 */
@Composable
fun ExactField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    var shown by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = exactKeyboard.copy(keyboardType = if (secret) KeyboardType.Password else keyboard),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (secret) Small(if (shown) "Masquer" else "Afficher") { shown = !shown }
                Small("Coller") { clipboard.getText()?.text?.trim()?.let(onChange) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Small(text: String, onClick: () -> Unit) =
    Text(text, color = LocalKitColors.current.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp))

/** Where a PIN is typed: four digits or more. */
@Composable
fun KitPinDialog(title: String, subtitle: String?, error: String?, onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember(title) { mutableStateOf("") }
    KitDialog(title, onDismiss, subtitle, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KitButton("Annuler", modifier = Modifier.weight(1f), onClick = onDismiss)
            KitButton("Valider", primary = true, modifier = Modifier.weight(1f)) { if (pin.length >= 4) { onPin(pin); pin = "" } }
        }
    }) {
        OutlinedTextField(pin, { v -> pin = v.filter { it.isDigit() }.take(8) }, label = { Text("Code PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = LocalKitColors.current.danger) }
    }
}
