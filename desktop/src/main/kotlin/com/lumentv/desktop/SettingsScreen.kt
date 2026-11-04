package com.lumentv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lumentv.core.userMessage
import com.lumentv.data.xtream.XtreamApi
import com.lumentv.sync.ProfileKeys
import com.lumentv.sync.SyncPlaylist
import com.lumentv.sync.WrongPassphraseException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val languages = listOf("" to "Automatique", "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "de" to "Allemand", "it" to "Italien")

@Composable
fun SettingsScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Paramètres", style = MaterialTheme.typography.headlineSmall, color = Pal.text) }

        if (state.syncEnabled) item {
            Card("Profil en ligne") {
                val name = state.profileName
                if (name == null) {
                    Text("Connectez le même profil que sur la TV : playlists, favoris, historique et réglages se retrouvent ici, chiffrés de bout en bout.", color = Pal.dim)
                    Button({ login = true }) { Text("Se connecter à un profil") }
                } else {
                    Text("Profil « $name »" + (state.syncMessage?.let { " · $it" } ?: if (state.lastSync > 0) " · synchronisé à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(state.lastSync)) else ""),
                        color = if (state.syncMessage != null) Pal.danger else Pal.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ scope.launch { state.syncNow().onSuccess { state.show("Profil synchronisé") } } }, enabled = !state.syncing) {
                            Text(if (state.syncing) "Synchronisation…" else "Synchroniser maintenant")
                        }
                        OutlinedButton({ state.signOut() }) { Text("Se déconnecter") }
                    }
                }
            }
        }

        item {
            Card("Playlists") {
                if (state.playlists.isEmpty()) Text("Aucune playlist sur ce PC.", color = Pal.dim)
                state.playlists.forEach { (key, p) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name + if (key == state.activeKey || (state.activeKey == null && p == state.active)) "  · active" else "", color = Pal.text)
                            Text((if (p.type == "xtream") "Xtream" else "M3U") + (state.catalog?.takeIf { p == state.active }?.let { c ->
                                " · ${c.channels.size} chaînes · ${c.movies.size} films · ${c.series.size} séries · chargée le " +
                                    SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(c.loadedAt))
                            } ?: ""), color = Pal.dim, style = MaterialTheme.typography.bodySmall)
                            if (p == state.active) state.loadError?.let { Text(it, color = Pal.danger, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (p != state.active) OutlinedButton({ state.open(key) }) { Text("Utiliser") }
                        OutlinedButton({ state.open(key, force = true) }, enabled = !state.loading) { Text(if (state.loading && p == state.active) "Chargement…" else "Recharger") }
                        TextButton({ state.removePlaylist(key) }) { Text("Supprimer", color = Pal.danger) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ adding = "xtream" }) { Text("+ Compte Xtream") }
                    OutlinedButton({ adding = "m3u" }) { Text("+ Playlist M3U") }
                }
            }
        }

        item {
            Card("Lecture") {
                Choice("Langue audio préférée", state.setting("audio_lang"), languages) { state.setSetting("audio_lang", it) }
                Choice("Langue des sous-titres", state.setting("sub_lang"), languages) { state.setSetting("sub_lang", it) }
                Toggle("Sous-titres activés", state.setting("subs_on") == "true") { state.setSetting("subs_on", it.toString()) }
                Toggle("Épisode suivant automatique", state.setting("auto_next", "true") != "false") { state.setSetting("auto_next", it.toString()) }
                Text("Ces réglages sont partagés avec la TV par le profil.", color = Pal.dim, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Card("Affichage et son") {
                var api by remember { mutableStateOf(Store.renderApi) }
                Choice("Moteur d'affichage", api, listOf("OPENGL" to "OpenGL (recommandé)", "DIRECT3D" to "DirectX", "SOFTWARE" to "Logiciel (le plus sûr, plus lent)")) {
                    Store.renderApi = it; api = it
                }
                Text("À changer si vous voyez des traits noirs ou des clignotements. Pris en compte au prochain lancement, sur ce PC seulement.",
                    color = Pal.dim, style = MaterialTheme.typography.bodySmall)
                var aout by remember { mutableStateOf(Store.audioOut) }
                Choice("Sortie audio", aout, listOf("directsound" to "DirectSound (recommandé)", "mmdevice" to "WASAPI", "waveout" to "WaveOut (ancien)")) {
                    Store.audioOut = it; aout = it
                }
                Text("À changer si le son grésille ou se hache. Pris en compte au prochain lancement, sur ce PC seulement.",
                    color = Pal.dim, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Card("Confidentialité") {
                // Read again every few seconds: servers are moved to HTTPS as the app reaches them.
                val summary by androidx.compose.runtime.produceState(com.lumentv.core.PrivateNet.summary()) {
                    while (true) { kotlinx.coroutines.delay(5_000); value = com.lumentv.core.PrivateNet.summary() }
                }
                Text(summary.first, color = Pal.text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(summary.second, color = Pal.dim, style = MaterialTheme.typography.bodySmall)
                Text("Votre fournisseur d'accès voit que vous utilisez internet et combien, pas ce que vous regardez quand la connexion est protégée.",
                    color = Pal.dim, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Card("À propos") {
                Text("Lumen TV ${BuildInfo.VERSION} pour Windows · lecture par le moteur VLC intégré.", color = Pal.text)
                Text("Raccourcis du lecteur : Espace pause · ←/→ 10 s · ↑/↓ volume · F plein écran · N épisode suivant · Échap fermer.", color = Pal.dim)
                Text("Gratuit, sans publicité ni télémétrie. Aucune chaîne n'est fournie. Identifiants chiffrés par Windows sur ce PC.", color = Pal.dim)
            }
        }
    }
    if (login) LoginDialog(state) { login = false }
    adding?.let { type -> PlaylistDialog(state, type) { adding = null } }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.panel).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Pal.accent)
        content()
    }
}

@Composable
private fun Choice(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.text, modifier = Modifier.weight(1f))
        OutlinedButton({ open = true }) { Text(options.firstOrNull { it.first == value }?.second ?: value) }
        DropdownMenu(open, { open = false }) { options.forEach { (k, l) -> DropdownMenuItem({ Text(l) }, { onPick(k); open = false }) } }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = Pal.text, modifier = Modifier.weight(1f)); Switch(value, onChange)
}

@Composable
private fun LoginDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, containerColor = Pal.panel,
        title = { Text("Profil en ligne") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(460.dp)) {
                Text("Même nom et même phrase secrète que sur la TV. Un nom nouveau crée le profil. La phrase secrète ne quitte jamais vos appareils et ne peut pas être récupérée si elle est oubliée.", color = Pal.dim)
                OutlinedTextField(name, { name = it }, label = { Text("Nom du profil") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Phrase secrète (${ProfileKeys.MIN_PASSPHRASE} caractères minimum)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = Pal.danger) }
            }
        },
        confirmButton = {
            Button({
                busy = true; error = null
                scope.launch {
                    state.signIn(name, pass).onSuccess { state.show("Profil « ${state.profileName} » connecté"); onClose() }
                        .onFailure { error = if (it is IllegalArgumentException || it is WrongPassphraseException) it.message else userMessage(it) }
                    busy = false
                }
            }, enabled = !busy && name.isNotBlank() && pass.length >= ProfileKeys.MIN_PASSPHRASE) { Text(if (busy) "Connexion…" else "Se connecter / créer") }
        },
        dismissButton = { TextButton({ if (!busy) onClose() }) { Text("Annuler") } })
}

@Composable
private fun PlaylistDialog(state: AppState, type: String, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    val xtream = type == "xtream"
    AlertDialog(onDismissRequest = onClose, containerColor = Pal.panel,
        title = { Text(if (xtream) "Compte Xtream" else "Playlist M3U") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(460.dp)) {
                OutlinedTextField(source, { source = it }, label = { Text(if (xtream) "Adresse du serveur" else "Adresse de la playlist (ou chemin d'un fichier)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (xtream) {
                    OutlinedTextField(user, { user = it }, label = { Text("Nom d'utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                } else OutlinedTextField(epg, { epg = it }, label = { Text("Guide TV XMLTV (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Nom affiché (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button({
                val src = if (xtream) XtreamApi.normalizeServer(source.trim()) else source.trim()
                state.addPlaylist(SyncPlaylist(
                    name = name.trim().ifBlank { if (xtream) src.substringAfter("://").substringBefore(':').substringBefore('/') else src.substringAfterLast('/').substringBefore('?').ifBlank { "Ma playlist" } },
                    type = type, source = src, username = if (xtream) user.trim() else "", password = if (xtream) pass else "", epgUrl = epg.trim(),
                ))
                onClose()
            }, enabled = source.isNotBlank() && (!xtream || (user.isNotBlank() && pass.isNotBlank()))) { Text("Enregistrer et charger") }
        },
        dismissButton = { TextButton(onClose) { Text("Annuler") } })
}

