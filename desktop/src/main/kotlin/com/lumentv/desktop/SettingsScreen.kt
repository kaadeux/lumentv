package com.lumentv.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lumentv.core.NetworkCheck
import com.lumentv.core.asHttp
import com.lumentv.data.xtream.XtreamApi
import com.lumentv.data.xtream.XtreamCredentials
import com.lumentv.sync.SyncPlaylist
import com.lumentv.ui.kit.KitButton
import com.lumentv.ui.kit.KitChip
import com.lumentv.ui.kit.KitDialog
import com.lumentv.ui.kit.SettingKey
import com.lumentv.ui.kit.SettingsList
import com.lumentv.ui.kit.SettingsModel
import com.lumentv.ui.kit.SettingsPage
import com.lumentv.ui.kit.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))
private fun day(t: Long) = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(t))

/**
 * The PC's data and actions for the shared settings page. The same keys as the other apps in the profile's settings.
 */
private class PcSettingsModel(private val app: AppState, private val onAdd: (String) -> Unit, private val onEdit: (String) -> Unit) : SettingsModel {
    @Composable override fun state(): SettingsState {
        val catalog = app.catalog
        return SettingsState(
            app.profileName,
            app.syncMessage ?: if (app.syncing) "Synchronisation…" else if (app.lastSync > 0) "Synchronisé à " + hm(app.lastSync) else "Pas encore synchronisé",
            app.syncMessage != null,
            app.playlists.map { (key, p) ->
                val c = catalog?.takeIf { p == app.active }
                SettingsList(key, p.name, p.type == "xtream", key == app.activeKeyOrFirst, c?.channels?.size ?: 0, c?.movies?.size ?: 0, c?.series?.size ?: 0,
                    c?.let { day(it.loadedAt) }, null, p.refreshHours, app.loading && p == app.active, app.loadError?.takeIf { p == app.active }, false, " par Windows")
            },
            app.setting("auto_next", "true") != "false", app.setting("auto_quality", "true") != "false", app.setting("buffer") == "stable", app.setting("subs_on") == "true",
            app.setting("audio_lang"), app.setting("sub_lang"), app.setting("resize", "0").toIntOrNull() ?: 0, app.setting("startup", "home"), app.setting("user_agent"),
            app.setting("epg_days", "3").toIntOrNull() ?: 3, app.parental.showHidden, app.parental.hasPin,
        )
    }

    override val profileEnabled get() = app.syncEnabled
    override suspend fun signIn(name: String, passphrase: String) = app.signIn(name, passphrase)
    override suspend fun syncNow() { app.syncNow().onSuccess { app.show("Profil synchronisé") } }
    override fun signOut() = app.signOut()

    override fun addList(isXtream: Boolean) = onAdd(if (isXtream) "xtream" else "m3u")
    override fun editList(key: String) = onEdit(key)
    override fun useList(key: String) = app.open(key)
    override fun reloadList(key: String) = app.open(key, force = true)
    override fun deleteList(key: String) = app.removePlaylist(key)
    override fun setReloadHours(key: String, hours: Int) { app.playlists[key]?.let { app.replacePlaylist(key, it.copy(refreshHours = hours), reload = false) } }
    override suspend fun encryptList(key: String) = true // Windows keeps them encrypted.

    override fun privacy() = com.lumentv.core.PrivateNet.summary()
    override val privacyNote get() = com.lumentv.core.Privacy.NOTE
    override suspend fun networkReport() = NetworkCheck.run(app.http.asHttp(), app.active?.takeIf { it.type == "xtream" }?.let { list ->
        { XtreamApi(app.http.asHttp(), XtreamCredentials(list.source, list.username, list.password), list.ua.ifBlank { null }).authenticate() }
    })

    override fun set(key: SettingKey, on: Boolean) {
        when (key) {
            SettingKey.AUTO_NEXT -> app.setSetting("auto_next", on.toString())
            SettingKey.AUTO_QUALITY -> app.setSetting("auto_quality", on.toString())
            SettingKey.SHOW_HIDDEN -> app.parental.setShowHidden(on)
            SettingKey.STABLE_BUFFER -> app.setSetting("buffer", if (on) "stable" else "normal")
            SettingKey.SUBTITLES_ON -> app.setSetting("subs_on", on.toString())
            else -> Unit
        }
    }
    override fun set(key: SettingKey, value: String) {
        when (key) {
            SettingKey.AUDIO_LANG -> app.setSetting("audio_lang", value)
            SettingKey.SUBTITLE_LANG -> app.setSetting("sub_lang", value)
            SettingKey.STARTUP -> app.setSetting("startup", value)
            SettingKey.USER_AGENT -> app.setSetting("user_agent", value)
            else -> Unit
        }
    }
    override fun setEpgDays(days: Int) { app.setSetting("epg_days", days.toString()); app.reloadGuide() }
    override fun setResizeMode(mode: Int) = app.setSetting("resize", mode.toString())
    override fun reloadGuide() = app.reloadGuide()
    override fun clearGuide() = app.clearGuide()

    override val parentalHint get() = "Verrouille ou masque un groupe de chaînes, ou masque une catégorie, avec le ⋮ à côté de son nom. Une chaîne se masque depuis sa fiche, à droite."
    override suspend fun checkPin(pin: String) = app.parental.checkPin(pin)
    override suspend fun setPin(pin: String) = app.parental.setPin(pin)

    override suspend fun cacheBytes() = withContext(Dispatchers.IO) {
        (SingletonImageLoader.get(PlatformContext.INSTANCE).diskCache?.size ?: 0L) + (app.vod.tmdbHttp.cache?.size() ?: 0L)
    }
    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        SingletonImageLoader.get(PlatformContext.INSTANCE).let { it.memoryCache?.clear(); it.diskCache?.clear() }
        app.vod.tmdbHttp.cache?.evictAll()
        app.vod.logos.forget()
    }

    override val deviceWord get() = "ce PC"
    override val footer get() = "Lumen TV ${BuildInfo.VERSION} pour Windows"

    @OptIn(ExperimentalLayoutApi::class)
    override val atEnd: List<Pair<String, @Composable () -> Unit>> get() = listOf(
        "Sur ce PC" to {
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
        },
        "À propos" to {
            Text("Lumen TV ${BuildInfo.VERSION} pour Windows · lecture par le moteur VLC intégré.", color = Pal.text)
            Text("Raccourcis du lecteur : Espace pause · ←/→ 10 s · ↑/↓ volume · F plein écran · N épisode suivant · Échap fermer.", color = Pal.dim, fontSize = 14.sp)
            Text("Gratuit, sans publicité ni télémétrie. Aucune chaîne n'est fournie.", color = Pal.dim, fontSize = 14.sp)
        },
    )
}

/** The settings: the page shared with the TV, the phone and the iPhone (:uikit). */
@Composable
fun SettingsScreen(state: AppState) {
    var adding by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    val model = remember(state) { PcSettingsModel(state, { adding = it }, { editing = it }) }
    Column(Modifier.fillMaxSize()) {
        Text("Paramètres", style = MaterialTheme.typography.headlineSmall, color = Pal.text)
        SettingsPage(model, Modifier.weight(1f), maxWidth = 760.dp)
    }
    adding?.let { type -> PlaylistDialog(state, type, null, null) { adding = null } }
    editing?.let { key -> state.playlists[key]?.let { p -> PlaylistDialog(state, p.type, key, p) { editing = null } } }
}

/** Adding a list, or changing one ([key], [old]). */
@Composable
private fun PlaylistDialog(state: AppState, type: String, key: String?, old: SyncPlaylist?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(old?.name.orEmpty()) }
    var source by remember { mutableStateOf(old?.source.orEmpty()) }
    var user by remember { mutableStateOf(old?.username.orEmpty()) }
    var pass by remember { mutableStateOf(old?.password.orEmpty()) }
    var epg by remember { mutableStateOf(old?.epgUrl.orEmpty()) }
    val xtream = type == "xtream"
    KitDialog(if (xtream) "Compte Xtream" else "Playlist M3U", onClose, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KitButton("Annuler", modifier = Modifier.weight(1f), onClick = onClose)
            KitButton("Enregistrer et charger", primary = true, modifier = Modifier.weight(1f)) {
                if (source.isBlank() || (xtream && (user.isBlank() || pass.isBlank()))) return@KitButton
                val src = if (xtream) XtreamApi.normalizeServer(source.trim()) else source.trim()
                val list = (old ?: SyncPlaylist(name = "", type = type, source = "")).copy(
                    name = name.trim().ifBlank { if (xtream) src.substringAfter("://").substringBefore(':').substringBefore('/') else src.substringAfterLast('/').substringBefore('?').ifBlank { "Ma playlist" } },
                    type = type, source = src, username = if (xtream) user.trim() else "", password = if (xtream) pass else "", epgUrl = epg.trim(),
                )
                if (key != null) state.replacePlaylist(key, list) else state.addPlaylist(list)
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
