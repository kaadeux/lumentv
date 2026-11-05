package com.lumentv.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The icon's orange, the same on every app (the PC's accent is blue). */
val BrandOrange = androidx.compose.ui.graphics.Color(0xFFFF7A2F)

/** The title "lumenTV•": "lumen" bold, "TV" light, the orange dot (as in the apps' title bars and the icon). */
@Composable
fun Wordmark(size: TextUnit = 34.sp) = Row(verticalAlignment = Alignment.Bottom) {
    val c = LocalKitColors.current
    Text("lumen", color = c.text, fontSize = size, fontWeight = FontWeight.Bold)
    Text("TV", color = c.text, fontSize = size, fontWeight = FontWeight.Light)
    Box(Modifier.padding(start = (size.value * 0.06f).dp, bottom = (size.value * 0.24f).dp).size((size.value * 0.16f).dp).clip(CircleShape).background(BrandOrange))
}

/** [length] digits, each in its own box (the one to type next outlined), typed with the keyboard. */
@Composable
fun CodeBoxes(value: String, onChange: (String) -> Unit, length: Int = 6, error: Boolean = false, onDone: () -> Unit = {}) {
    val c = LocalKitColors.current
    val focus = remember { FocusRequester() }
    BasicTextField(
        value, { v -> onChange(v.filter { it.isDigit() }.take(length)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent), cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.focusRequester(focus),
        decorationBox = { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(length) { i ->
                    val next = i == value.length
                    Box(Modifier.size(width = 48.dp, height = 58.dp).clip(RoundedCornerShape(12.dp)).background(c.panelHigh)
                        .border(2.dp, when { error -> c.danger; next -> c.accent; else -> androidx.compose.ui.graphics.Color.Transparent }, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        if (i < value.length) Box(Modifier.size(12.dp).clip(CircleShape).background(c.text))
                    }
                }
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * The family access code, asked once per device (PC, iPhone): the title, the six boxes, checked as soon as the sixth
 * digit is in. [check] says whether the code is right (and remembers it).
 */
@Composable
fun AccessGateScreen(check: (String) -> Boolean, onUnlocked: () -> Unit) {
    val c = LocalKitColors.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun submit() { if (code.length == 6) { if (check(code)) onUnlocked() else { error = true; code = "" } } }
    Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.panel).padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark(40.sp)
            Spacer(Modifier.height(18.dp))
            Text("Application réservée à la famille", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("Entre le code d'accès à 6 chiffres. Il n'est demandé qu'une fois sur cet appareil.", color = c.dim, fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
            CodeBoxes(code, { code = it; error = false; if (it.length == 6) submit() }, error = error, onDone = { submit() })
            Text(if (error) "Code incorrect, réessaie." else " ", color = c.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(8.dp))
            KitButton("Valider", primary = true, modifier = Modifier.fillMaxWidth()) { submit() }
        }
    }
}
