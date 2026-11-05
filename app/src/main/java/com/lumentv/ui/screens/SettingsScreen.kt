package com.lumentv.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import com.lumentv.BuildConfig
import com.lumentv.core.userMessage
import com.lumentv.data.settings.Keys
import com.lumentv.phone.CacheOps
import com.lumentv.phone.SettingsButton
import com.lumentv.phone.SettingsChoice
import com.lumentv.phone.SettingsExtra
import com.lumentv.phone.SettingsExtras
import com.lumentv.phone.SettingsMenu
import com.lumentv.phone.SettingsNote
import com.lumentv.phone.SettingsToggle
import com.lumentv.phone.SharedSettings
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.Screen
import com.lumentv.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The settings: the iPhone's page (phoneui, shared with the phone and the TV: profile, lists, parental control,
 * privacy, playback, guide, storage), with what only Android has as sections of its own (updates, the TV's picture,
 * the connection test, backup, diagnostics).
 */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val phoneDevice = com.lumentv.ui.LocalPhone.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var network by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var restorePicker by remember { mutableStateOf(false) }
    val backupFile = remember { File(context.getExternalFilesDir(null), "lumen-sauvegarde.json") }
    // The background reload follows each list's choice (set in the shared page).
    LaunchedEffect(playlists?.map { it.id to it.refreshHours }) { vm.syncWorker() }
    val stats by produceState("", playlists) {
        val g = vm.graph
        value = "${g.db.channels().observeTotal().first()} chaînes · ${g.db.vod().observeMovieTotal().first()} films · " +
            "${g.db.vod().observeSeriesTotal().first()} séries · ${g.db.epg().observeTotal().first()} programmes"
    }

    val extras = SettingsExtras(
        afterLists = listOfNotNull(
            if (vm.graph.updater.enabled) SettingsExtra("Mises à jour") {
                SettingsNote("Version ${BuildConfig.VERSION_NAME} · " + if (BuildConfig.CHANNEL == "dev") "canal de test" else "canal famille")
                SettingsButton("Rechercher une mise à jour") {
                    scope.launch {
                        vm.show("Recherche d'une mise à jour…")
                        vm.graph.updater.check()
                            .onSuccess { if (it == null) vm.show("Lumen TV est à jour (${BuildConfig.VERSION_NAME}).") }
                            .onFailure { vm.show("Impossible de vérifier les mises à jour : ${userMessage(it)}") }
                    }
                }
            } else null,
        ),
        atEnd = listOf(
            SettingsExtra(if (phoneDevice) "Sur ce téléphone" else "Sur cette TV") {
                SettingsToggle("Mémoire tampon stable", "Pour une connexion faible : démarre un peu plus lentement, coupe moins.",
                    settings.bufferMode == "stable") { on -> scope.launch { vm.graph.settings.set(Keys.buffer, if (on) "stable" else "normal") } }
                if (!phoneDevice) SettingsToggle("Fréquence de l'écran adaptée", "L'écran suit la vidéo (25 ou 50 images par seconde) : image fluide.",
                    settings.matchFrameRate) { on -> scope.launch { vm.graph.settings.set(Keys.matchFrameRate, on) } }
                SettingsNote("Format d'image")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Adapter", 1 to "Zoom", 2 to "Étirer").forEach { (m, label) ->
                        SettingsChoice(label, settings.resizeMode == m) { scope.launch { vm.graph.settings.set(Keys.resize, m) } }
                    }
                }
                SettingsNote("Au lancement")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsChoice("L'accueil", settings.startup != "last") { scope.launch { vm.graph.settings.set(Keys.startup, "home") } }
                    SettingsChoice("La dernière chaîne", settings.startup == "last") { scope.launch { vm.graph.settings.set(Keys.startup, "last") } }
                }
                SettingsButton("User-Agent : " + (userAgents.firstOrNull { it.first == settings.defaultUserAgent }?.second ?: "personnalisé")) { menu = "ua" }
                SettingsButton("Tester ma connexion") { network = true }
                SettingsButton("Effacer le guide TV enregistré") { menu = "clearepg" }
            },
            SettingsExtra("Sauvegarde") {
                SettingsButton("Exporter (sans identifiants)") { scope.launch { exportBackup(vm, backupFile, false) } }
                SettingsButton("Exporter avec identifiants et adresses") { scope.launch { exportBackup(vm, backupFile, true) } }
                SettingsButton("Restaurer une sauvegarde") { restorePicker = true }
                SettingsNote("Fichier : ${backupFile.absolutePath}")
            },
            SettingsExtra("À propos et diagnostic") {
                SettingsNote("Lumen TV ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                SettingsNote(stats)
                playlists.orEmpty().forEach { p -> p.lastError?.let { SettingsNote("${p.name} : $it") } }
                SettingsNote("Lecteur gratuit, sans publicité, sans compte et sans télémétrie. Aucune chaîne n'est fournie. " +
                    "Les identifiants sont chiffrés sur l'appareil et masqués dans les journaux.")
            },
        ),
        footer = "Lumen TV ${BuildConfig.VERSION_NAME}",
        privacy = { com.lumentv.core.PrivateNet.summary() },
        privacyNote = null,
        networkTest = false,
        parentalHint = if (phoneDevice) "Verrouille un groupe de chaînes : appui long sur le groupe dans l'onglet Direct. Masque une chaîne ou un groupe de la même façon."
            else "Verrouille un groupe de chaînes : maintiens OK sur le groupe dans l'écran Direct. Masque une chaîne ou un groupe de la même façon.",
        cache = CacheOps(
            sizeBytes = { withContext(Dispatchers.IO) { context.imageLoader.diskCache?.size ?: 0L } },
            clear = { context.imageLoader.memoryCache?.clear(); context.imageLoader.diskCache?.clear() },
        ),
    )
    SharedSettings(vm.graph.core, onEditList = { id, type -> vm.open(Screen.PlaylistEdit(id, type)) }, extras,
        maxWidth = if (phoneDevice) androidx.compose.ui.unit.Dp.Unspecified else 820.dp)

    if (network) com.lumentv.ui.NetworkPanel(vm.graph, null, onDismiss = { network = false })
    when (menu) {
        "ua" -> SettingsMenu("User-Agent par défaut", "Certains fournisseurs n'acceptent que certains lecteurs.",
            userAgents.map { (v, l) -> l to (v == settings.defaultUserAgent) }, onPick = { i ->
                scope.launch { vm.graph.settings.set(Keys.userAgent, userAgents[i].first) }
            }) { menu = null }
        "clearepg" -> ConfirmDialog("Effacer le guide TV ?", "Il sera de nouveau téléchargé à la prochaine actualisation.", "Effacer",
            onConfirm = { scope.launch { vm.graph.epg.clearAll(); vm.show("Guide TV effacé") } }, onDismiss = { menu = null })
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

private val userAgents = listOf(
    "" to "Automatique (Lumen TV)", "VLC/3.0.20 LibVLC/3.0.20" to "VLC", "IPTVSmarters/1.0" to "IPTV Smarters",
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" to "Navigateur Chrome",
)

private suspend fun exportBackup(vm: MainViewModel, file: File, secrets: Boolean) {
    runCatching { vm.graph.backup.export(file, secrets) }
        .onSuccess { vm.show("Sauvegarde de $it playlist(s) écrite dans ${file.name}") }
        .onFailure { vm.show("Échec de la sauvegarde : ${userMessage(it)}") }
}

fun resizeLabel(m: Int) = when (m) { 1 -> "Zoom (remplir l'écran)"; 2 -> "Étirer"; else -> "Adapter (bandes noires)" }
