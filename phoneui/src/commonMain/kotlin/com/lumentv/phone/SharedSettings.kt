package com.lumentv.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.lumentv.AppCore

/** A section a device adds to the shared settings (Android: updates, backup, the TV's picture…). */
class SettingsExtra(val title: String, val content: @Composable () -> Unit)

class SettingsExtras(
    /** After Mes listes. */
    val afterLists: List<SettingsExtra> = emptyList(),
    /** Before the last line. */
    val atEnd: List<SettingsExtra> = emptyList(),
    val footer: String = "Lumen TV pour iPhone",
    /** What the internet provider can still see (the iPhone's HttpsUpgrade; the JVM's PrivateNet on Android). */
    val privacy: () -> com.lumentv.core.Privacy = { com.lumentv.core.HttpsUpgrade.summary() },
    val privacyNote: String? = "Le moteur vidéo FFmpeg (MKV, direct en TS) peut encore demander le nom du serveur au DNS de la box.",
    val parentalHint: String = "Verrouille un groupe de chaînes avec le ⋮ à côté de son nom dans l'onglet TV. Masque une chaîne, un groupe ou une catégorie de la même façon.",
    /** The pictures' cache: its size and emptying it (the device's image loader). Null: the shared one. */
    val cache: CacheOps? = null,
    /** The shared "État du réseau" section (Android has its own panel, with the playing stream's figures). */
    val networkTest: Boolean = true,
)

class CacheOps(val sizeBytes: suspend () -> Long, val clear: suspend () -> Unit)

/**
 * The iPhone's settings page, for the other apps built on [AppCore] (the Android TV and phone): the same sections and
 * look everywhere, plus the device's own [extras]. A list to add or change goes to [onEditList] (the device's screen).
 */
@Composable
fun SharedSettings(core: AppCore, onEditList: (id: Long, type: String) -> Unit, extras: SettingsExtras, maxWidth: Dp = Dp.Unspecified) = LumenTheme {
    val playlists by core.playlists.observe().collectAsState(emptyList())
    val settings by core.settings.flow.collectAsState(null)
    val active = playlists.firstOrNull { it.id == settings?.activePlaylistId } ?: playlists.firstOrNull()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        SettingsPage(core, active, open = { r -> if (r is Route.PlaylistEdit) onEditList(r.id, r.type) }, extras, Modifier.widthIn(max = maxWidth).fillMaxWidth())
    }
    PinHost(core)
}

/** The shared pieces a device uses in its [SettingsExtra]s, so they look like the rest. */
@Composable
fun SettingsButton(text: String, primary: Boolean = false, onClick: () -> Unit) = PhoneButton(text, primary, Modifier.fillMaxWidth(), onClick)

@Composable
fun SettingsChoice(label: String, selected: Boolean, onClick: () -> Unit) = PhoneChip(label, selected, onClick)

@Composable
fun SettingsToggle(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) = Toggle(title, subtitle, on, onChange)

@Composable
fun SettingsNote(text: String) = androidx.compose.material3.Text(text, color = Palette.TextDim, fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp))

@Composable
fun SettingsMenu(title: String, subtitle: String?, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit, onDismiss: () -> Unit) =
    Options(title, subtitle, options.mapIndexed { i, (label, checked) -> Option(label, checked) { onPick(i) } }, onDismiss)
