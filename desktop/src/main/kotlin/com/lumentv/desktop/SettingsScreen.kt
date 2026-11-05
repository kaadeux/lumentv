package com.lumentv.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumentv.core.userMessage
import com.lumentv.data.xtream.XtreamApi
import com.lumentv.sync.ProfileKeys
import com.lumentv.sync.SyncPlaylist
import com.lumentv.sync.WrongPassphraseException
import com.lumentv.ui.kit.KitButton
import com.lumentv.ui.kit.KitDialog
import com.lumentv.ui.kit.KitMenu
import com.lumentv.ui.kit.KitOption
import com.lumentv.ui.kit.KitChip
import com.lumentv.ui.kit.KitToggle
import com.lumentv.ui.kit.ListTile
import com.lumentv.ui.kit.PrivacyLines
import com.lumentv.ui.kit.SecretsLine
import com.lumentv.ui.kit.SettingsSection
import com.lumentv.ui.kit.StatTiles
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val languages = listOf("" to "Automatique", "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "de" to "Allemand", "it" to "Italien")


private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))
private fun day(t: Long) = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(t))

/** The settings, laid out as on the iPhone, the TV and the phone (shared pieces of :uikit). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Text("Paramètres", style = MaterialTheme.typography.headlineSmall, color = Pal.text) }

        if (state.syncEnabled) item {
            SettingsSection("Profil en ligne") {
                val name = state.profileName
                if (name == null && state.playlists.isEmpty()) {
                    Text(com.lumentv.core.ProfileGate.NEEDS_LIST, color = Pal.dim, fontSize = 14.sp)
                } else if (name == null) {
                    Text("Le même profil que sur la TV : playlists, favoris, historique et réglages se retrouvent ici, chiffrés de bout en bout.",
                        color = Pal.dim, fontSize = 14.sp)
                    KitButton("Se connecter", primary = true, modifier = Modifier.fillMaxWidth()) { login = true }
                } else {
                    Text(name, color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.syncMessage ?: if (state.syncing) "Synchronisation…" else if (state.lastSync > 0) "Synchronisé à " + hm(state.lastSync) else "Pas encore synchronisé",
                        color = if (state.syncMessage != null) Pal.danger else Pal.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KitButton("Synchroniser", primary = true, modifier = Modifier.weight(1f)) {
                            if (!state.syncing) scope.launch { state.syncNow().onSuccess { state.show("Profil synchronisé") } }
                        }
                        KitButton("Se déconnecter", modifier = Modifier.weight(1f)) { state.signOut() }
                    }
                }
            }
        }

        val active = state.active
        val catalog = state.catalog
        if (active != null) item {
            SettingsSection("Liste active") {
                Text(active.name, color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (catalog != null) {
                    StatTiles(listOf("${catalog.channels.size}" to "chaînes", "${catalog.movies.size}" to "films", "${catalog.series.size}" to "séries"))
                    Text("Chargée le " + day(catalog.loadedAt), color = Pal.dim, fontSize = 14.sp)
                }
                state.loadError?.let { Text(it, color = Pal.danger, fontSize = 14.sp) }
                KitButton(if (state.loading) "Chargement…" else "Recharger la liste", modifier = Modifier.fillMaxWidth()) { if (!state.loading) state.reload() }
            }
        }

        item {
            SettingsSection("Mes listes") {
                if (state.playlists.isEmpty()) Text("Aucune liste sur ce PC : ajoute ta playlist M3U ou ton compte Xtream.", color = Pal.dim, fontSize = 14.sp)
                state.playlists.forEach { (key, p) ->
                    val isActive = key == state.activeKeyOrFirst
                    ListTile(p.name, if (p.type == "xtream") "Compte Xtream" else "Playlist M3U", isActive, onClick = { menu = key }) {
                        // On the PC the login is kept by Windows (DPAPI), always encrypted.
                        SecretsLine(false, " par Windows")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KitChip("+ Playlist M3U") { adding = "m3u" }
                    KitChip("+ Compte Xtream") { adding = "xtream" }
                }
            }
        }

        item {
            SettingsSection("Confidentialité vis-à-vis du FAI") {
                // Read again every few seconds: servers are moved to HTTPS as the app reaches them.
                val summary by androidx.compose.runtime.produceState(com.lumentv.core.PrivateNet.summary()) {
                    while (true) { kotlinx.coroutines.delay(5_000); value = com.lumentv.core.PrivateNet.summary() }
                }
                PrivacyLines(summary.title, summary.lines, summary.partial, com.lumentv.core.Privacy.NOTE)
            }
        }

        item {
            SettingsSection("Lecture") {
                KitToggle("Épisode suivant automatique", "À la fin d'un épisode, le suivant se lance.", state.setting("auto_next", "true") != "false") {
                    state.setSetting("auto_next", it.toString())
                }
                KitToggle("Sous-titres activés", "Sinon, seulement les sous-titres forcés.", state.setting("subs_on") == "true") { state.setSetting("subs_on", it.toString()) }
                Text("Langue audio", color = Pal.dim, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    languages.forEach { (code, label) -> KitChip(label, state.setting("audio_lang") == code) { state.setSetting("audio_lang", code) } }
                }
                Text("Langue des sous-titres", color = Pal.dim, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    languages.forEach { (code, label) -> KitChip(label, state.setting("sub_lang") == code) { state.setSetting("sub_lang", code) } }
                }
                Text("Partagés avec la TV par le profil.", color = Pal.dim, fontSize = 13.sp)
            }
        }

        item {
            SettingsSection("Sur ce PC") {
                var api by remember { mutableStateOf(Store.renderApi) }
                Text("Moteur d'affichage", color = Pal.dim, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("OPENGL" to "OpenGL (recommandé)", "DIRECT3D" to "DirectX", "SOFTWARE" to "Logiciel").forEach { (k, l) ->
                        KitChip(l, api == k) { Store.renderApi = k; api = k }
                    }
                }
                Text("À changer si l'image a des traits noirs ou clignote. Au prochain lancement.", color = Pal.dim, fontSize = 13.sp)
                var aout by remember { mutableStateOf(Store.audioOut) }
                Text("Sortie audio", color = Pal.dim, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("directsound" to "DirectSound (recommandé)", "mmdevice" to "WASAPI", "waveout" to "WaveOut").forEach { (k, l) ->
                        KitChip(l, aout == k) { Store.audioOut = k; aout = k }
                    }
                }
                Text("À changer si le son grésille ou se hache. Au prochain lancement.", color = Pal.dim, fontSize = 13.sp)
            }
        }

        item {
            SettingsSection("À propos") {
                Text("Lumen TV ${BuildInfo.VERSION} pour Windows · lecture par le moteur VLC intégré.", color = Pal.text)
                Text("Raccourcis du lecteur : Espace pause · ←/→ 10 s · ↑/↓ volume · F plein écran · N épisode suivant · Échap fermer.", color = Pal.dim, fontSize = 14.sp)
                Text("Gratuit, sans publicité ni télémétrie. Aucune chaîne n'est fournie.", color = Pal.dim, fontSize = 14.sp)
            }
        }
    }
    }
    menu?.let { key ->
        val p = state.playlists[key]
        if (p == null) { menu = null; return@let }
        KitMenu(p.name, listOfNotNull(
            if (key != state.activeKeyOrFirst) KitOption("Utiliser cette liste") { state.open(key) } else null,
            KitOption("Recharger") { state.open(key, force = true) },
            KitOption("Supprimer", danger = true) { state.removePlaylist(key) },
        ), onDismiss = { menu = null }, subtitle = if (p.type == "xtream") "Compte Xtream" else "Playlist M3U")
    }
    if (login) LoginDialog(state) { login = false }
    adding?.let { type -> PlaylistDialog(state, type) { adding = null } }
}

@Composable
private fun LoginDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    KitDialog("Profil en ligne", { if (!busy) onClose() },
        "Même nom et même phrase secrète que sur la TV. Un nom nouveau crée le profil. La phrase secrète ne quitte jamais vos appareils et ne peut pas être récupérée si elle est oubliée.",
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KitButton("Annuler", modifier = Modifier.weight(1f)) { if (!busy) onClose() }
                KitButton(if (busy) "Connexion…" else "Se connecter / créer", primary = true, modifier = Modifier.weight(1f)) {
                    if (busy || name.isBlank() || pass.length < ProfileKeys.MIN_PASSPHRASE) return@KitButton
                    busy = true; error = null
                    scope.launch {
                        state.signIn(name, pass).onSuccess { state.show("Profil « ${state.profileName} » connecté"); onClose() }
                            .onFailure { error = if (it is IllegalArgumentException || it is WrongPassphraseException) it.message else userMessage(it) }
                        busy = false
                    }
                }
            }
        }) {
        OutlinedTextField(name, { name = it }, label = { Text("Nom du profil") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Phrase secrète (${ProfileKeys.MIN_PASSPHRASE} caractères minimum)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = Pal.danger) }
    }
}

@Composable
private fun PlaylistDialog(state: AppState, type: String, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    val xtream = type == "xtream"
    KitDialog(if (xtream) "Compte Xtream" else "Playlist M3U", onClose, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KitButton("Annuler", modifier = Modifier.weight(1f), onClick = onClose)
            KitButton("Enregistrer et charger", primary = true, modifier = Modifier.weight(1f)) {
                if (source.isBlank() || (xtream && (user.isBlank() || pass.isBlank()))) return@KitButton
                val src = if (xtream) XtreamApi.normalizeServer(source.trim()) else source.trim()
                state.addPlaylist(SyncPlaylist(
                    name = name.trim().ifBlank { if (xtream) src.substringAfter("://").substringBefore(':').substringBefore('/') else src.substringAfterLast('/').substringBefore('?').ifBlank { "Ma playlist" } },
                    type = type, source = src, username = if (xtream) user.trim() else "", password = if (xtream) pass else "", epgUrl = epg.trim(),
                ))
                onClose()
            }
        }
    }) {
        OutlinedTextField(source, { source = it }, label = { Text(if (xtream) "Adresse du serveur" else "Adresse de la playlist (ou chemin d'un fichier)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (xtream) {
            OutlinedTextField(user, { user = it }, label = { Text("Nom d'utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        } else OutlinedTextField(epg, { epg = it }, label = { Text("Guide TV XMLTV (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Nom affiché (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

