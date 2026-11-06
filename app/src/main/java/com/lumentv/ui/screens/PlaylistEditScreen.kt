@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import com.lumentv.ui.components.ButtonRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.core.SecretBox
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.xtream.XtreamApi
import com.lumentv.data.xtream.XtreamCredentials
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.ScreenTitle
import com.lumentv.ui.components.SettingRow
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.components.TvTextField
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun PlaylistEditScreen(vm: MainViewModel, id: Long, initialType: String) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(id == 0L) }
    var existing by remember { mutableStateOf<PlaylistEntity?>(null) }
    var type by remember { mutableStateOf(initialType) }
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("http://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("ts") }
    var refreshHours by remember { mutableIntStateOf(24) }
    var offset by remember { mutableIntStateOf(0) }
    var dedupe by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var detected by remember { mutableStateOf<XtreamCredentials?>(null) }

    LaunchedEffect(id) {
        if (id != 0L) vm.graph.playlists.get(id)?.let { p ->
            existing = p; type = p.type; name = p.name; source = SecretBox.decrypt(p.sourceEnc); user = p.username
            pass = SecretBox.decrypt(p.passwordEnc); epgUrl = SecretBox.decrypt(p.epgUrlEnc); userAgent = p.userAgent
            format = p.liveFormat; refreshHours = p.refreshHours; offset = p.epgOffsetMinutes; dedupe = p.dedupe
        }
        if (id == 0L && type == PlaylistType.XTREAM) source = "http://"
        loaded = true
    }
    if (!loaded) return

    fun save() {
        error = null
        val src = source.trim()
        if (type == PlaylistType.M3U && (src.isBlank() || src == "http://")) { error = "Indiquez l'adresse de la playlist ou choisissez un fichier."; return }
        if (type == PlaylistType.XTREAM && (src.isBlank() || src == "http://" || user.isBlank() || pass.isBlank())) {
            error = "Le serveur, le nom d'utilisateur et le mot de passe sont obligatoires."; return
        }
        val p = (existing ?: PlaylistEntity(name = "", type = type, sourceEnc = "")).copy(
            name = name.trim().ifBlank { if (type == PlaylistType.XTREAM) XtreamApi.normalizeServer(src).substringAfter("://").substringBefore(':') else src.substringAfterLast('/').substringBefore('?').ifBlank { "Ma playlist" } },
            type = type,
            sourceEnc = SecretBox.encrypt(if (type == PlaylistType.XTREAM) XtreamApi.normalizeServer(src) else src),
            username = if (type == PlaylistType.XTREAM) user.trim() else "",
            passwordEnc = if (type == PlaylistType.XTREAM) SecretBox.encrypt(pass) else "",
            epgUrlEnc = SecretBox.encrypt(epgUrl.trim().takeIf { it != "http://" }.orEmpty()),
            userAgent = userAgent.trim(), liveFormat = format, refreshHours = refreshHours, epgOffsetMinutes = offset, dedupe = dedupe,
        )
        scope.launch {
            val newId = vm.graph.playlists.save(p)
            vm.graph.resolver.forget()
            vm.graph.epg.invalidate(newId)
            vm.setActive(newId)
            vm.back()
            vm.refresh(newId)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle(if (id == 0L) "Nouvelle playlist" else "Modifier la playlist") }
        item {
            ButtonRow {
                TvButton((if (type == PlaylistType.M3U) "● " else "") + "Lien ou fichier M3U", { type = PlaylistType.M3U })
                TvButton((if (type == PlaylistType.XTREAM) "● " else "") + "Xtream Codes", { type = PlaylistType.XTREAM })
            }
        }
        item { TvTextField("Nom affiché", name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Automatique", keyboardType = KeyboardType.Text) }
        if (type == PlaylistType.M3U) {
            item {
                TvTextField("Adresse de la playlist (M3U / M3U8)", source, { source = it }, Modifier.fillMaxWidth(), onDone = {
                    XtreamApi.fromM3uLink(source)?.let { c -> detected = c; menu = "convert" }
                })
            }
            item { TvButton("… ou choisir un fichier sur l'appareil", { picker = "m3u" }) }
        } else {
            item { TvTextField("Adresse du serveur", source, { source = it }, Modifier.fillMaxWidth(), placeholder = "http://exemple.com:8080") }
            item { TvTextField("Nom d'utilisateur", user, { user = it }, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Text) }
            item { TvTextField("Mot de passe", pass, { pass = it }, Modifier.fillMaxWidth(), password = true) }
            item { SettingRow("Format des chaînes en direct", if (format == "ts") "MPEG-TS (.ts)" else "HLS (.m3u8)", { format = if (format == "ts") "m3u8" else "ts" }) }
        }
        item {
            TvTextField("Guide TV XMLTV (facultatif)", epgUrl, { epgUrl = it }, Modifier.fillMaxWidth(),
                placeholder = if (type == PlaylistType.XTREAM) "Automatique (guide du serveur Xtream)" else "Automatique (url-tvg de la playlist)")
        }
        item { TvButton("… ou choisir un fichier XMLTV", { picker = "epg" }) }
        item { SettingRow("Actualisation automatique", refreshLabel(refreshHours), { menu = "refresh" }) }
        item { SettingRow("Décalage du guide TV", offsetLabel(offset), { menu = "offset" }) }
        if (type == PlaylistType.M3U) item { SettingRow("Ignorer les doublons (même adresse)", if (dedupe) "Oui" else "Non", { dedupe = !dedupe }) }
        item { TvTextField("User-Agent HTTP (facultatif)", userAgent, { userAgent = it }, Modifier.fillMaxWidth(), placeholder = "Par défaut", keyboardType = KeyboardType.Text) }
        error?.let { item { Text(it, color = Palette.Danger, style = MaterialTheme.typography.bodyLarge) } }
        item {
            ButtonRow(Modifier.padding(top = 8.dp, bottom = 24.dp)) {
                TvButton("Enregistrer et charger", { save() }, primary = true)
                TvButton("Annuler", { vm.back() })
            }
        }
    }

    when (menu) {
        "refresh" -> OptionsDialog("Actualisation automatique", REFRESH_CHOICES.map { h ->
            MenuOption(refreshLabel(h), h == refreshHours) { refreshHours = h }
        }, onDismiss = { menu = null })
        "offset" -> OptionsDialog("Décalage du guide TV", (-12..12).map { it * 60 }.let { listOf(0) + it.filter { m -> m != 0 } }.sortedBy { it }.map { m ->
            MenuOption(offsetLabel(m), m == offset) { offset = m }
        }, onDismiss = { menu = null })
        "convert" -> OptionsDialog("Lien Xtream détecté", listOf(
            MenuOption("Utiliser Xtream Codes (films, séries, rattrapage, guide)") {
                detected?.let { c -> type = PlaylistType.XTREAM; source = c.server; user = c.username; pass = c.password }
            },
            MenuOption("Garder le lien M3U") {},
        ), onDismiss = { menu = null }, subtitle = "Ce lien contient des identifiants Xtream. Le mode Xtream donne accès à plus de contenu.")
    }

    picker?.let { purpose ->
        FileBrowserDialog(
            if (purpose == "m3u") "Choisir une playlist" else "Choisir un guide XMLTV",
            if (purpose == "m3u") setOf("m3u", "m3u8", "txt") else setOf("xml", "gz", "xmltv"),
            onPick = { f -> if (purpose == "m3u") { type = PlaylistType.M3U; source = f.absolutePath; if (name.isBlank()) name = f.nameWithoutExtension } else epgUrl = f.absolutePath; picker = null },
            onDismiss = { picker = null },
        )
    }
}

fun offsetLabel(minutes: Int) = if (minutes == 0) "Aucun" else (if (minutes > 0) "+" else "−") + "${kotlin.math.abs(minutes) / 60} h"

/** Automatic reload intervals offered for a playlist, in hours (0 = off). */
val REFRESH_CHOICES = listOf(0, 6, 12, 24, 48, 168)

fun refreshLabel(h: Int) = when (h) { 0 -> "Désactivée"; 24 -> "Toutes les 24 h (chaque jour)"; 168 -> "Chaque semaine"; else -> "Toutes les $h h" }
