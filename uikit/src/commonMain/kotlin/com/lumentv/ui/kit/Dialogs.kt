package com.lumentv.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** One choice of a [KitMenu]: its label, a tick when it is the current one, and [danger] for "Supprimer". */
class KitOption(val label: String, val checked: Boolean = false, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * The window every app shows over a page (TV and phone, PC, iPhone): a title, an optional text, then [content] and
 * [buttons], in the settings' cards' look. [rootModifier] lets the TV keep its own key handling.
 */
@Composable
fun KitDialog(
    title: String, onDismiss: () -> Unit, subtitle: String? = null, rootModifier: Modifier = Modifier,
    buttons: @Composable () -> Unit = {}, content: @Composable () -> Unit = {},
) = Dialog(onDismissRequest = onDismiss) {
    val c = LocalKitColors.current
    Column(rootModifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.panel).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, color = c.dim, fontSize = 14.sp, maxLines = 8, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
            }
        }
        content()
        buttons()
    }
}

/**
 * A short menu (an episode's actions, a list's, a group to pick…): each choice a tile, "Fermer" below. The focus starts
 * on the current choice (or the first), for the TV's remote. [closeOnPick] false keeps it open (several to tick).
 */
@Composable
fun KitMenu(
    title: String, options: List<KitOption>, onDismiss: () -> Unit, subtitle: String? = null,
    closeOnPick: Boolean = true, rootModifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    val start = options.indexOfFirst { it.checked }.coerceAtLeast(0)
    KitDialog(title, onDismiss, subtitle, rootModifier, buttons = { KitButton("Fermer", modifier = Modifier.fillMaxWidth(), onClick = onDismiss) }) {
        val c = LocalKitColors.current
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o ->
                Row(Modifier.fillMaxWidth().then(if (i == start) Modifier.focusRequester(first) else Modifier)
                    .pressable(RoundedCornerShape(12.dp)) { if (closeOnPick) onDismiss(); o.onClick() }
                    .background(if (o.checked) c.accentSoft else c.panelHigh).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(o.label, color = if (o.danger) c.danger else c.text, fontSize = 16.sp,
                        fontWeight = if (o.checked) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (o.checked) Text("✓", color = c.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** A long text in full (a summary cut on the page), "Fermer" below. */
@Composable
fun KitTextDialog(title: String, text: String, onDismiss: () -> Unit, rootModifier: Modifier = Modifier) {
    val close = remember { FocusRequester() }
    KitDialog(title, onDismiss, rootModifier = rootModifier,
        buttons = { KitButton("Fermer", primary = true, modifier = Modifier.fillMaxWidth().focusRequester(close), onClick = onDismiss) }) {
        Text(text, color = LocalKitColors.current.text, fontSize = 15.sp, modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()))
    }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

/** A question with its answer: [confirm] (white, or red when [danger]) and "Annuler". */
@Composable
fun KitConfirm(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
               danger: Boolean = false, rootModifier: Modifier = Modifier) {
    val cancel = remember { FocusRequester() }
    KitDialog(title, onDismiss, message, rootModifier, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KitButton("Annuler", modifier = Modifier.weight(1f).focusRequester(cancel), onClick = onDismiss)
            if (danger) KitDangerButton(confirm, Modifier.weight(1f)) { onDismiss(); onConfirm() }
            else KitButton(confirm, primary = true, modifier = Modifier.weight(1f)) { onDismiss(); onConfirm() }
        }
    })
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
}

/** The red button of a deletion. */
@Composable
fun KitDangerButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    androidx.compose.foundation.layout.Box(modifier.heightIn(min = 46.dp).pressable(RoundedCornerShape(10.dp), onClick)
        .background(LocalKitColors.current.danger), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp))
    }

