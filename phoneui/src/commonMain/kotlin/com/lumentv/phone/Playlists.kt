package com.lumentv.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumentv.AppCore
import com.lumentv.core.SecretBox
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.settings.Keys
import com.lumentv.data.xtream.XtreamApi
import kotlinx.coroutines.launch

private val REFRESH_CHOICES = listOf(0 to "Jamais", 6 to "6 h", 12 to "12 h", 24 to "24 h", 48 to "48 h", 168 to "7 j")

private fun offsetLabel(minutes: Int) = if (minutes == 0) "Aucun" else (if (minutes > 0) "+" else "−") + "${kotlin.math.abs(minutes) / 60} h"

/** The list's secrets as stored: "p:" means the Keychain refused and they are kept in clear. */
private fun PlaylistEntity.secrets() = listOf(sourceEnc, passwordEnc, epgUrlEnc, autoEpgUrlEnc).filter { it.isNotEmpty() }
private fun PlaylistEntity.inClear() = secrets().any { it.startsWith("p:") }

/** The same list with its secrets encrypted again (once the Keychain works). */
private fun PlaylistEntity.reEncrypted() = copy(
    sourceEnc = SecretBox.encrypt(SecretBox.decrypt(sourceEnc)), passwordEnc = SecretBox.encrypt(SecretBox.decrypt(passwordEnc)),
    epgUrlEnc = SecretBox.encrypt(SecretBox.decrypt(epgUrlEnc)), autoEpgUrlEnc = SecretBox.encrypt(SecretBox.decrypt(autoEpgUrlEnc)),
)

private fun typeLabel(type: String) = if (type == PlaylistType.XTREAM) "Compte Xtream" else "Playlist M3U"

/** Adding or changing a list, from the Paramètres. */
@Composable
internal fun PlaylistEditPage(core: AppCore, id: Long, type: String, back: () -> Unit) {
    val existing by produceState<PlaylistEntity?>(null, id) { if (id != 0L) value = core.playlists.get(id) }
    if (id != 0L && existing == null) Centered { CircularProgressIndicator() }
    else PlaylistForm(core, existing, type, Modifier.fillMaxSize().padding(16.dp), onSaved = back)
}

/**
 * The Android phone's playlist screen: an M3U address or an Xtream account, then the guide, the automatic reload,
 * the guide offset and the rest. No file on the iPhone: an M3U list is added by its address.
 */
@Composable
internal fun PlaylistForm(core: AppCore, existing: PlaylistEntity?, startType: String, modifier: Modifier, onSaved: () -> Unit) {
    val e = existing
    var type by remember(e?.id) { mutableStateOf(e?.type ?: startType) }
    var name by remember(e?.id) { mutableStateOf(e?.name ?: "") }
    var source by remember(e?.id) { mutableStateOf(e?.let { SecretBox.decrypt(it.sourceEnc) } ?: "http://") }
    var user by remember(e?.id) { mutableStateOf(e?.username ?: "") }
    var pass by remember(e?.id) { mutableStateOf(e?.passwordEnc?.takeIf { it.isNotEmpty() }?.let { SecretBox.decrypt(it) } ?: "") }
    var epgUrl by remember(e?.id) { mutableStateOf(e?.epgUrlEnc?.takeIf { it.isNotEmpty() }?.let { SecretBox.decrypt(it) } ?: "") }
    var userAgent by remember(e?.id) { mutableStateOf(e?.userAgent ?: "") }
    var format by remember(e?.id) { mutableStateOf(e?.liveFormat ?: "ts") }
    var refreshHours by remember(e?.id) { mutableStateOf(e?.refreshHours ?: 24) }
    var offset by remember(e?.id) { mutableStateOf(e?.epgOffsetMinutes ?: 0) }
    var dedupe by remember(e?.id) { mutableStateOf(e?.dedupe ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val xtream = type == PlaylistType.XTREAM
    // An M3U link of an Xtream panel (get.php?username=…): better read as the account itself.
    val asXtream = if (!xtream) XtreamApi.fromM3uLink(source.trim()) else null

    Column(modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneChip("Playlist M3U", selected = !xtream) { type = PlaylistType.M3U; error = null }
            PhoneChip("Compte Xtream", selected = xtream) { type = PlaylistType.XTREAM; error = null }
        }
        Text(if (xtream) "Le serveur, l'identifiant et le mot de passe de ton fournisseur. Ils restent sur cet iPhone, chiffrés."
            else "L'adresse de ta playlist (http://…/liste.m3u). Elle reste sur cet iPhone, chiffrée.", color = Palette.TextDim, fontSize = 14.sp)
        ExactField(name, { name = it }, "Nom (facultatif)")
        if (xtream) {
            ExactField(source, { source = it }, "Serveur (http://…:port)", keyboard = KeyboardType.Uri)
            ExactField(user, { user = it }, "Identifiant")
            ExactField(pass, { pass = it }, "Mot de passe", secret = true)
            Text("Format du direct", color = Palette.TextDim, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneChip("TS", selected = format == "ts") { format = "ts" }
                PhoneChip("HLS (m3u8)", selected = format == "m3u8") { format = "m3u8" }
            }
        } else {
            ExactField(source, { source = it }, "Adresse de la playlist", keyboard = KeyboardType.Uri)
            asXtream?.let { c ->
                Column(Modifier.fillMaxWidth().clickable {
                    type = PlaylistType.XTREAM; source = c.server; user = c.username; pass = c.password
                }.padding(vertical = 4.dp)) {
                    Text("Ce lien vient d'un compte Xtream", fontWeight = FontWeight.SemiBold)
                    Text("Toucher pour l'ajouter comme compte Xtream : films, séries et rattrapage en plus.", color = Palette.Accent, fontSize = 14.sp)
                }
            }
            Toggle("Fusionner les doublons", "Une même chaîne présente plusieurs fois n'apparaît qu'une fois.", dedupe) { dedupe = it }
        }
        ExactField(epgUrl, { epgUrl = it }, "Guide TV XMLTV (facultatif)", keyboard = KeyboardType.Uri)
        Text("Décalage du guide : ${offsetLabel(offset)}", color = Palette.TextDim, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneChip("− 1 h") { offset = (offset - 60).coerceAtLeast(-720) }
            PhoneChip("Aucun", selected = offset == 0) { offset = 0 }
            PhoneChip("+ 1 h") { offset = (offset + 60).coerceAtMost(720) }
        }
        Text("Rechargement automatique", color = Palette.TextDim, fontSize = 14.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            REFRESH_CHOICES.forEach { (h, label) -> item { PhoneChip(label, selected = refreshHours == h) { refreshHours = h } } }
        }
        ExactField(userAgent, { userAgent = it }, "User-Agent (facultatif)")
        error?.let { Text(it, color = Palette.Danger) }
        PhoneButton(if (saving) "Enregistrement…" else if (e == null) "Ajouter et charger" else "Enregistrer et recharger", primary = true,
            modifier = Modifier.fillMaxWidth()) {
            if (saving) return@PhoneButton
            val src = source.trim()
            if (xtream && (src.length < 10 || user.isBlank() || pass.isBlank())) {
                error = "Le serveur, l'identifiant et le mot de passe sont obligatoires."; return@PhoneButton
            }
            if (!xtream && (!(src.startsWith("http://", true) || src.startsWith("https://", true)) || src.length < 12)) {
                error = "Indique l'adresse de la playlist (http://…)."; return@PhoneButton
            }
            saving = true; error = null
            scope.launch {
                val server = if (xtream) XtreamApi.normalizeServer(src) else src
                val fallback = if (xtream) server.substringAfter("://").substringBefore(':').substringBefore('/')
                    else src.substringAfterLast('/').substringBefore('?').ifBlank { "Ma playlist" }
                val guide = epgUrl.trim().takeIf { it.length > 8 }
                val base = e ?: PlaylistEntity(name = "", type = type, sourceEnc = "")
                val id = core.playlists.save(base.copy(
                    name = name.trim().ifBlank { fallback }, type = type, sourceEnc = SecretBox.encrypt(server),
                    username = if (xtream) user.trim() else "", passwordEnc = if (xtream) SecretBox.encrypt(pass) else "",
                    epgUrlEnc = guide?.let { SecretBox.encrypt(it) } ?: "", userAgent = userAgent.trim(),
                    liveFormat = format, refreshHours = refreshHours, epgOffsetMinutes = offset, dedupe = dedupe,
                ))
                core.resolver.forget()
                core.epg.invalidate(id)
                core.settings.set(Keys.activePlaylist, id)
                // A new list is read by the first-load screen; a changed one again here, in the app's scope.
                if (e != null && e.lastRefresh > 0) core.scope.launch { if (core.playlists.refresh(id).ok) core.epg.refresh(id) }
                saving = false
                onSaved()
            }
        }
    }
}

/** The Paramètres' lists: the one in use, the others, and adding one. */
@Composable
internal fun PlaylistsSection(core: AppCore, active: PlaylistEntity, open: (Route) -> Unit) {
    val list by core.playlists.observe().collectAsState(emptyList())
    val busy by core.playlists.busy.collectAsState()
    var menu by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleting by remember { mutableStateOf<PlaylistEntity?>(null) }
    Section("Mes listes") {
        list.forEach { p ->
            Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Palette.PanelHigh)
                .clickable { menu = p }.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(listOfNotNull(typeLabel(p.type), if (p.id in busy) "actualisation…" else null,
                        p.lastError?.takeIf { it.isNotBlank() }?.let { "erreur" }).joinToString(" · "),
                        color = Palette.TextDim, fontSize = 13.sp)
                    if (p.inClear()) Text("⚠️  Identifiants non chiffrés", color = Palette.Danger, fontSize = 13.sp)
                    else Text("🔒  Identifiants chiffrés", color = Palette.TextDim, fontSize = 13.sp)
                }
                if (p.id == active.id) Text("Active", color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Palette.AccentSoft).padding(horizontal = 8.dp, vertical = 3.dp))
                else Text("⋮", color = Palette.TextDim, fontSize = 18.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneChip("+ Playlist M3U") { open(Route.PlaylistEdit(0, PlaylistType.M3U)) }
            PhoneChip("+ Compte Xtream") { open(Route.PlaylistEdit(0, PlaylistType.XTREAM)) }
        }
    }
    menu?.let { p ->
        Options(p.name, typeLabel(p.type), listOfNotNull(
            if (p.id != active.id) Option("Utiliser cette liste") { core.scope.launch { core.settings.set(Keys.activePlaylist, p.id) } } else null,
            Option("Actualiser") { core.scope.launch { if (core.playlists.refresh(p.id).ok) core.epg.refresh(p.id) } },
            Option("Modifier") { open(Route.PlaylistEdit(p.id, p.type)) },
            if (p.inClear()) Option("Chiffrer les identifiants") {
                core.scope.launch {
                    val again = p.reEncrypted()
                    core.playlists.save(again)
                    Parental.notice = if (again.inClear()) "Le trousseau de l'iPhone refuse encore : les identifiants restent non chiffrés."
                        else "Identifiants chiffrés."
                }
            } else null,
            Option("Supprimer") { deleting = p },
        )) { menu = null }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Palette.Panel,
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("Ses chaînes, films, séries, son guide et son historique sont retirés de cet iPhone.", color = Palette.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    core.scope.launch {
                        val next = list.firstOrNull { it.id != p.id }
                        if (p.id == active.id && next != null) core.settings.set(Keys.activePlaylist, next.id)
                        core.playlists.delete(p.id)
                    }
                }) { Text("Supprimer", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}
