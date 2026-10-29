@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.imageLoader
import com.lumentv.BuildConfig
import com.lumentv.core.userMessage
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.settings.Keys
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.Screen
import com.lumentv.ui.components.ConfirmDialog
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.PinDialog
import com.lumentv.ui.components.ScreenTitle
import com.lumentv.ui.components.SettingRow
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val languages = listOf("" to "Automatique", "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "de" to "Allemand",
    "it" to "Italien", "pt" to "Portugais", "ar" to "Arabe", "nl" to "Néerlandais")

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<String?>(null) }
    var playlistMenu by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleting by remember { mutableStateOf<PlaylistEntity?>(null) }
    var autoFor by remember { mutableStateOf<PlaylistEntity?>(null) }
    val profile by vm.graph.profile.state.collectAsStateWithLifecycle()
    var profileDialog by remember { mutableStateOf(false) }
    var profileBusy by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var pinStep by remember { mutableStateOf<String?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var newPin by remember { mutableStateOf<String?>(null) }
    var restorePicker by remember { mutableStateOf(false) }
    val backupFile = remember { File(context.getExternalFilesDir(null), "lumen-sauvegarde.json") }
    val stats by produceState("", playlists) {
        val g = vm.graph
        value = "${g.db.channels().observeTotal().first()} chaînes · ${g.db.vod().observeMovieTotal().first()} films · " +
            "${g.db.vod().observeSeriesTotal().first()} séries · ${g.db.epg().observeTotal().first()} programmes"
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { ScreenTitle("Paramètres") }

        item { Group("Playlists") }
        val list = playlists.orEmpty()
        if (list.isEmpty()) item {
            Text("Bienvenue ! Ajoutez votre playlist M3U ou votre compte Xtream Codes pour commencer. Lumen TV ne fournit aucune chaîne : " +
                "utilisez les listes de votre propre abonnement.", style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(list, key = { it.id }) { p ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaylistCard(p, p.id == active?.id, p.id in busy) { playlistMenu = p }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val running = p.id in busy
                    TvButton(if (running) "Rechargement en cours…" else "Recharger la liste", { if (!running) vm.refresh(p.id) },
                        icon = Icons.Filled.Refresh, primary = p.id == active?.id)
                    TvButton("Rechargement auto : " + refreshLabel(p.refreshHours), { autoFor = p })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                TvButton("Ajouter une playlist M3U", { vm.open(Screen.PlaylistEdit(0, PlaylistType.M3U)) }, icon = Icons.Filled.Add, primary = list.isEmpty())
                TvButton("Ajouter un compte Xtream", { vm.open(Screen.PlaylistEdit(0, PlaylistType.XTREAM)) }, icon = Icons.Filled.Add)
            }
        }

        if (vm.graph.profile.enabled) {
            item { Group("Profil en ligne") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (profile.name == null) {
                        TvButton("Se connecter à un profil", { profileError = null; profileDialog = true }, icon = Icons.Filled.Add)
                        Text("Retrouvez vos favoris et votre historique sur tous vos appareils.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                    } else {
                        TvButton(if (profile.syncing) "Synchronisation…" else "Synchroniser maintenant", {
                            scope.launch { vm.graph.profile.syncNow().onSuccess { vm.show("Profil synchronisé") } }
                        }, icon = Icons.Filled.Refresh)
                        TvButton("Se déconnecter", { vm.graph.profile.signOut(); vm.show("Profil déconnecté de cette TV") })
                        Text("Profil « ${profile.name} »" + (profile.error?.let { " · $it" }
                            ?: if (profile.lastSync > 0) " · synchronisé à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(profile.lastSync)) else ""),
                            style = MaterialTheme.typography.bodyMedium, color = if (profile.error != null) Palette.Danger else Palette.TextDim)
                    }
                }
            }
        }

        if (vm.graph.updater.enabled) {
            item { Group("Mises à jour") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton("Rechercher une mise à jour", {
                        scope.launch {
                            vm.show("Recherche d'une mise à jour…")
                            vm.graph.updater.check()
                                .onSuccess { if (it == null) vm.show("Lumen TV est à jour (${BuildConfig.VERSION_NAME}).") }
                                .onFailure { vm.show("Impossible de vérifier les mises à jour : ${userMessage(it)}") }
                        }
                    }, icon = Icons.Filled.Refresh)
                    Text("Version ${BuildConfig.VERSION_NAME} · " + if (BuildConfig.CHANNEL == "dev") "canal de test" else "canal famille",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                }
            }
        }

        item {
            val (title, detail) = remember { com.lumentv.core.PrivateNet.summary() }
            SettingRow("Confidentialité : $title", detail, { vm.show("Votre fournisseur d'accès voit que vous utilisez internet et combien, pas ce que vous regardez quand la connexion est protégée.") })
        }
        item { Group("Lecture") }
        item { SettingRow("Mémoire tampon", if (settings.bufferMode == "stable") "Stable (connexion faible)" else "Normale (démarrage rapide)", {
            scope.launch { vm.graph.settings.set(Keys.buffer, if (settings.bufferMode == "stable") "normal" else "stable") } }) }
        item { SettingRow("Fréquence de l'écran", if (settings.matchFrameRate) "Adaptée à la vidéo (image fluide)" else "Fixe", {
            scope.launch { vm.graph.settings.set(Keys.matchFrameRate, !settings.matchFrameRate) } }) }
        item { SettingRow("Format d'image", resizeLabel(settings.resizeMode), { menu = "resize" }) }
        item { SettingRow("Langue audio préférée", languages.firstOrNull { it.first == settings.audioLang }?.second ?: settings.audioLang, { menu = "audio" }) }
        item { SettingRow("Sous-titres", if (settings.subtitlesOn) "Activés" else "Désactivés", {
            scope.launch { vm.graph.settings.set(Keys.subsOn, !settings.subtitlesOn) } }) }
        item { SettingRow("Langue des sous-titres", languages.firstOrNull { it.first == settings.subtitleLang }?.second ?: settings.subtitleLang, { menu = "subs" }) }
        item { SettingRow("Épisode suivant automatique", if (settings.autoNext) "Oui" else "Non", {
            scope.launch { vm.graph.settings.set(Keys.autoNext, !settings.autoNext) } }) }

        item { Group("Démarrage et affichage") }
        item { SettingRow("Au lancement", if (settings.startup == "last") "Reprendre la dernière chaîne" else "Afficher l'accueil", {
            scope.launch { vm.graph.settings.set(Keys.startup, if (settings.startup == "last") "home" else "last") } }) }
        item { SettingRow("Éléments masqués", if (settings.showHidden) "Affichés" else "Cachés", {
            scope.launch { vm.graph.settings.set(Keys.showHidden, !settings.showHidden) } }) }

        item { Group("Guide TV") }
        item { SettingRow("Jours de programmes à conserver", "${settings.epgDays} jours", { menu = "epgdays" }) }
        item { SettingRow("Effacer le guide TV enregistré", null, { menu = "clearepg" }) }

        item { Group("Contrôle parental") }
        item { SettingRow(if (settings.pinHash.isEmpty()) "Définir un code PIN" else "Changer le code PIN", null, { pinStep = if (settings.pinHash.isEmpty()) "new" else "current"; pinError = null }) }
        if (settings.pinHash.isNotEmpty()) item { SettingRow("Supprimer le code PIN", null, { pinStep = "remove"; pinError = null }) }
        item { Text("Maintenez OK sur un groupe de chaînes (écran Direct) pour le verrouiller.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(start = 4.dp)) }

        item { Group("Réseau") }
        item { SettingRow("User-Agent par défaut", settings.defaultUserAgent.ifBlank { "Automatique (Lumen TV)" }, { menu = "ua" }) }

        item { Group("Sauvegarde") }
        item { SettingRow("Exporter (sans identifiants)", null, { scope.launch { exportBackup(vm, backupFile, false) } }) }
        item { SettingRow("Exporter avec identifiants et adresses", null, { scope.launch { exportBackup(vm, backupFile, true) } }) }
        item { SettingRow("Restaurer une sauvegarde", null, { restorePicker = true }) }
        item { Text("Fichier : ${backupFile.absolutePath}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(start = 4.dp)) }

        item { Group("Stockage") }
        item { SettingRow("Vider le cache des images", null, {
            context.imageLoader.memoryCache?.clear(); context.imageLoader.diskCache?.clear(); vm.show("Cache des images vidé") }) }

        item { Group("À propos et diagnostic") }
        item {
            Column(Modifier.padding(start = 4.dp, bottom = 30.dp)) {
                Text("Lumen TV ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                Text(stats, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                list.forEach { p ->
                    p.lastError?.let { Text("${p.name} : $it", style = MaterialTheme.typography.bodySmall, color = Palette.Danger) }
                }
                Text("Lecteur gratuit, sans publicité, sans compte et sans télémétrie. Aucune chaîne n'est fournie. " +
                    "Les identifiants sont chiffrés sur l'appareil et masqués dans les journaux.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
    }

    playlistMenu?.let { p ->
        OptionsDialog(p.name, buildList {
            if (p.id != active?.id) add(MenuOption("Utiliser cette playlist") { vm.setActive(p.id) })
            add(MenuOption("Actualiser la liste et le guide") { vm.refresh(p.id) })
            add(MenuOption("Actualiser seulement le guide TV") { vm.refreshEpg(p.id) })
            add(MenuOption("Modifier") { vm.open(Screen.PlaylistEdit(p.id, p.type)) })
            add(MenuOption("Supprimer") { deleting = p })
        }, onDismiss = { playlistMenu = null }, subtitle = p.accountInfo)
    }
    if (profileDialog) com.lumentv.ui.components.ProfileDialog(profileBusy, profileError, onSubmit = { name, pass ->
        profileBusy = true; profileError = null
        scope.launch {
            vm.graph.profile.signIn(name, pass)
                .onSuccess { profileDialog = false; vm.show("Profil « ${vm.graph.profile.state.value.name} » connecté et synchronisé") }
                .onFailure { profileError = if (it is IllegalArgumentException || it is com.lumentv.sync.WrongPassphraseException) it.message else userMessage(it) }
            profileBusy = false
        }
    }, onDismiss = { profileDialog = false })
    autoFor?.let { p ->
        OptionsDialog("Rechargement automatique", REFRESH_CHOICES.map { h ->
            MenuOption(refreshLabel(h), h == p.refreshHours) {
                scope.launch { vm.graph.playlists.save(p.copy(refreshHours = h)); vm.syncWorker() }
            }
        }, onDismiss = { autoFor = null }, subtitle = "La liste et le guide TV sont retéléchargés en arrière-plan, sans interrompre la lecture.")
    }
    deleting?.let { p ->
        ConfirmDialog("Supprimer « ${p.name} » ?", "Ses chaînes, favoris, guide et historique seront supprimés de l'appareil.", "Supprimer",
            onConfirm = { scope.launch { vm.graph.playlists.delete(p.id); vm.graph.resolver.forget(); vm.syncWorker(); vm.show("Playlist supprimée") } },
            onDismiss = { deleting = null })
    }

    when (menu) {
        "resize" -> OptionsDialog("Format d'image", (0..2).map { m -> MenuOption(resizeLabel(m), m == settings.resizeMode) {
            scope.launch { vm.graph.settings.set(Keys.resize, m) } } }, onDismiss = { menu = null })
        "audio" -> OptionsDialog("Langue audio préférée", languages.map { (c, l) -> MenuOption(l, c == settings.audioLang) {
            scope.launch { vm.graph.settings.set(Keys.audioLang, c) } } }, onDismiss = { menu = null })
        "subs" -> OptionsDialog("Langue des sous-titres", languages.map { (c, l) -> MenuOption(l, c == settings.subtitleLang) {
            scope.launch { vm.graph.settings.set(Keys.subLang, c) } } }, onDismiss = { menu = null })
        "epgdays" -> OptionsDialog("Jours de programmes", listOf(1, 2, 3, 5, 7).map { d -> MenuOption("$d jours", d == settings.epgDays) {
            scope.launch { vm.graph.settings.set(Keys.epgDays, d) } } }, onDismiss = { menu = null })
        "ua" -> OptionsDialog("User-Agent par défaut", listOf(
            "" to "Automatique (Lumen TV)", "VLC/3.0.20 LibVLC/3.0.20" to "VLC", "IPTVSmarters/1.0" to "IPTV Smarters",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" to "Navigateur Chrome",
        ).map { (v, l) -> MenuOption(l, v == settings.defaultUserAgent) { scope.launch { vm.graph.settings.set(Keys.userAgent, v) } } },
            onDismiss = { menu = null }, subtitle = "Certains fournisseurs n'acceptent que certains lecteurs.")
        "clearepg" -> ConfirmDialog("Effacer le guide TV ?", "Il sera de nouveau téléchargé à la prochaine actualisation.", "Effacer",
            onConfirm = { scope.launch { vm.graph.epg.clearAll(); vm.show("Guide TV effacé") } }, onDismiss = { menu = null })
    }

    pinStep?.let { step ->
        PinDialog(when (step) { "current" -> "Code PIN actuel"; "new" -> "Nouveau code PIN (4 chiffres)"; "confirm" -> "Confirmez le nouveau code"; else -> "Code PIN actuel" },
            error = pinError, onDismiss = { pinStep = null; newPin = null }, onPin = { pin ->
                scope.launch {
                    when (step) {
                        "current" -> if (vm.graph.settings.checkPin(pin)) { pinStep = "new"; pinError = null } else pinError = "Code incorrect"
                        "new" -> { newPin = pin; pinStep = "confirm"; pinError = null }
                        "confirm" -> if (pin == newPin) { vm.graph.settings.setPin(pin); newPin = null; pinStep = null; vm.show("Code PIN enregistré") }
                            else { pinError = "Les codes ne correspondent pas, recommencez"; newPin = null; pinStep = "new" }
                        "remove" -> if (vm.graph.settings.checkPin(pin)) { vm.graph.settings.setPin(""); pinStep = null; vm.show("Code PIN supprimé") }
                            else pinError = "Code incorrect"
                    }
                }
            })
    }

    if (restorePicker) {
        FileBrowserDialog("Choisir une sauvegarde", setOf("json"), onPick = { f ->
            restorePicker = false
            scope.launch {
                runCatching { vm.graph.backup.import(f) }
                    .onSuccess { ids -> vm.show(if (ids.isEmpty()) "Sauvegarde sans identifiants : seuls les réglages ont été restaurés." else "${ids.size} playlist(s) restaurée(s)"); ids.forEach { vm.refresh(it) } }
                    .onFailure { vm.show("Sauvegarde illisible : ${userMessage(it)}") }
            }
        }, onDismiss = { restorePicker = false })
    }
}

private suspend fun exportBackup(vm: MainViewModel, file: File, secrets: Boolean) {
    runCatching { vm.graph.backup.export(file, secrets) }
        .onSuccess { vm.show("Sauvegarde de $it playlist(s) écrite dans ${file.name}") }
        .onFailure { vm.show("Échec de la sauvegarde : ${userMessage(it)}") }
}

@Composable
private fun Group(title: String) =
    Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Accent, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp))

@Composable
private fun PlaylistCard(p: PlaylistEntity, active: Boolean, busy: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick, Modifier.fillMaxWidth(), selected = active, focusedScale = 1.01f) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name + if (active) "  · active" else "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val counts = listOfNotNull("${p.channelCount} chaînes", p.movieCount.takeIf { it > 0 }?.let { "$it films" }, p.seriesCount.takeIf { it > 0 }?.let { "$it série" + if (it > 1) "s" else "" })
                Text(when (p.type) { PlaylistType.XTREAM -> "Xtream · "; else -> "M3U · " } + counts.joinToString(" · ") +
                    (if (p.lastRefresh > 0) " · actualisée le " + SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(p.lastRefresh)) else ""),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
                p.accountInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                p.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Danger, maxLines = 2) }
            }
            if (busy) Text("Actualisation…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun resizeLabel(m: Int) = when (m) { 1 -> "Zoom (remplir l'écran)"; 2 -> "Étirer"; else -> "Adapter (bandes noires)" }
