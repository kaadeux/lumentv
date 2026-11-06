package com.lumentv.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumentv.core.NetReport
import com.lumentv.core.NetworkCheck
import com.lumentv.core.Privacy
import com.lumentv.core.ProfileGate
import com.lumentv.core.SettingChoices as Choices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A list as the settings show it. */
class SettingsList(
    val key: String, val name: String, val isXtream: Boolean, val active: Boolean,
    val channels: Int, val movies: Int, val series: Int, val updated: String?, val account: String?,
    val reloadHours: Int, val busy: Boolean, val error: String?, val secretsInClear: Boolean, val secretsWhere: String = "",
)

/** What the settings show, read by each app from its own store. */
class SettingsState(
    val profileName: String?, val profileStatus: String?, val profileError: Boolean,
    val lists: List<SettingsList>,
    val autoNext: Boolean, val autoQuality: Boolean, val stableBuffer: Boolean, val subtitlesOn: Boolean,
    val audioLang: String, val subtitleLang: String, val resizeMode: Int, val startup: String, val userAgent: String,
    val epgDays: Int, val showHidden: Boolean, val hasPin: Boolean,
)

/**
 * One settings page for every app: an app gives its data and its actions here, the page is the same. A section of its
 * own goes in [afterLists] or [atEnd]; what an app cannot do yet is switched off by its flag.
 */
interface SettingsModel {
    @Composable fun state(): SettingsState

    val profileEnabled: Boolean
    suspend fun signIn(name: String, passphrase: String): Result<Unit>
    suspend fun syncNow()
    fun signOut()

    fun addList(isXtream: Boolean)
    fun editList(key: String)
    fun useList(key: String)
    fun reloadList(key: String)
    fun deleteList(key: String)
    fun setReloadHours(key: String, hours: Int)
    /** Encrypts a list's login again; true when it is encrypted now. */
    suspend fun encryptList(key: String): Boolean

    fun privacy(): Privacy
    val privacyNote: String
    suspend fun networkReport(): NetReport
    /** The app's own connection panel; null: the shared window. */
    val onNetworkTest: (() -> Unit)? get() = null

    fun set(key: SettingKey, on: Boolean)
    fun set(key: SettingKey, value: String)
    fun setEpgDays(days: Int)
    fun setResizeMode(mode: Int)
    fun reloadGuide()
    fun clearGuide()

    /** Not on every app yet (the PC): the rows are left out rather than shown doing nothing. */
    val hasParental: Boolean get() = true
    val hasAutoQuality: Boolean get() = true
    val hasResizeMode: Boolean get() = true
    val parentalHint: String get() = ""
    suspend fun checkPin(pin: String): Boolean = true
    suspend fun setPin(pin: String) {}

    suspend fun cacheBytes(): Long
    suspend fun clearCache()

    val deviceWord: String
    val footer: String
    val afterLists: List<Pair<String, @Composable () -> Unit>> get() = emptyList()
    val atEnd: List<Pair<String, @Composable () -> Unit>> get() = emptyList()
}

enum class SettingKey { AUTO_NEXT, AUTO_QUALITY, STABLE_BUFFER, SUBTITLES_ON, SHOW_HIDDEN, AUDIO_LANG, SUBTITLE_LANG, STARTUP, USER_AGENT }

private sealed interface PinStep { object Current : PinStep; object New : PinStep; class Confirm(val first: String) : PinStep; object Remove : PinStep; object ShowHidden : PinStep }

@Composable
fun SettingsPage(model: SettingsModel, modifier: Modifier = Modifier, maxWidth: Dp = Dp.Unspecified, listState: LazyListState = rememberLazyListState()) {
    val c = LocalKitColors.current
    val s = model.state()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var listMenu by remember { mutableStateOf<SettingsList?>(null) }
    var deleting by remember { mutableStateOf<SettingsList?>(null) }
    var clearingGuide by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var pinStep by remember { mutableStateOf<PinStep?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val active = s.lists.firstOrNull { it.active } ?: s.lists.firstOrNull()

    @Composable fun label(text: String) = Text(text, color = c.dim, fontSize = 14.sp)
    @Composable fun chips(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    @Composable fun button(text: String, primary: Boolean = false, onClick: () -> Unit) = KitButton(text, primary, Modifier.fillMaxWidth(), onClick)

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(Modifier.widthIn(max = maxWidth).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            var name by remember { mutableStateOf("") }
            var pass by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            var signing by remember { mutableStateOf(false) }
            SettingsSection("Profil en ligne") {
                when {
                    !model.profileEnabled -> label("Le profil en ligne n'est pas activé dans cette version de l'app.")
                    s.profileName == null && s.lists.isEmpty() -> label(ProfileGate.NEEDS_LIST)
                    s.profileName == null -> {
                        label("Le même profil partout : favoris, historique, reprise et notes suivent d'un appareil à l'autre. Chiffré sur ${model.deviceWord} avant l'envoi.")
                        ExactField(name, { name = it }, "Nom du profil")
                        ExactField(pass, { pass = it }, "Phrase secrète", secret = true)
                        error?.let { Text(it, color = c.danger) }
                        button(if (signing) "Connexion…" else "Se connecter", primary = true) {
                            if (signing || name.isBlank() || pass.isBlank()) return@button
                            signing = true; error = null
                            scope.launch {
                                model.signIn(name.trim(), pass).onFailure { error = it.message ?: "Connexion impossible." }
                                signing = false
                            }
                        }
                    }
                    else -> {
                        Text(s.profileName, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        s.profileStatus?.let { Text(it, color = if (s.profileError) c.danger else c.dim) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KitButton("Synchroniser", primary = true, modifier = Modifier.weight(1f)) { scope.launch { model.syncNow() } }
                            KitButton("Se déconnecter", modifier = Modifier.weight(1f)) { model.signOut() }
                        }
                    }
                }
            }
        }
        if (active != null) item {
            SettingsSection("Liste active") {
                Text(active.name, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                StatTiles(listOf("${active.channels}" to "chaînes", "${active.movies}" to "films", "${active.series}" to "séries"))
                active.updated?.let { label("Actualisée le $it") }
                active.account?.let { label(it) }
                active.error?.let { Text(it, color = c.danger, fontSize = 14.sp) }
                label("Rechargement automatique")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choices.reloadHours.forEach { (h, text) -> item { KitChip(text, active.reloadHours == h) { model.setReloadHours(active.key, h) } } }
                }
                button(if (active.busy) "Actualisation…" else "Recharger la liste") { if (!active.busy) model.reloadList(active.key) }
            }
        }
        item {
            SettingsSection("Mes listes") {
                if (s.lists.isEmpty()) label("Bienvenue ! Ajoute ta playlist M3U ou ton compte Xtream pour commencer. Lumen TV ne fournit aucune chaîne : utilise les listes de ton propre abonnement.")
                s.lists.forEach { l ->
                    ListTile(l.name, listOfNotNull(if (l.isXtream) "Compte Xtream" else "Playlist M3U", if (l.busy) "actualisation…" else null, if (l.error != null) "erreur" else null).joinToString(" · "),
                        l.active, onClick = { listMenu = l }) { SecretsLine(l.secretsInClear, l.secretsWhere) }
                }
                chips {
                    KitChip("+ Playlist M3U") { model.addList(false) }
                    KitChip("+ Compte Xtream") { model.addList(true) }
                }
            }
        }
        model.afterLists.forEach { (title, content) -> item { SettingsSection(title, content = content) } }
        if (model.hasParental) item {
            SettingsSection("Contrôle parental") {
                label(model.parentalHint)
                KitToggle("Afficher les éléments masqués", "Pour les retrouver et les afficher à nouveau.", s.showHidden) { on ->
                    if (on && s.hasPin) { pinError = null; pinStep = PinStep.ShowHidden } else model.set(SettingKey.SHOW_HIDDEN, on)
                }
                button(if (s.hasPin) "Changer le code PIN" else "Définir un code PIN") { pinError = null; pinStep = if (s.hasPin) PinStep.Current else PinStep.New }
                if (s.hasPin) button("Supprimer le code PIN") { pinError = null; pinStep = PinStep.Remove }
            }
        }
        item {
            // Read again every few seconds: servers are moved to HTTPS as the app reaches them.
            val privacy by produceState(model.privacy()) { while (true) { delay(5_000); value = model.privacy() } }
            SettingsSection("Confidentialité vis-à-vis du FAI") { PrivacyLines(privacy.title, privacy.lines, privacy.partial, model.privacyNote) }
        }
        item {
            SettingsSection("Réseau") {
                label("Internet et le serveur du fournisseur, chronométrés, puis la cause probable d'une coupure.")
                button("Tester ma connexion") { model.onNetworkTest?.invoke() ?: run { testing = true } }
                button("User-Agent : " + Choices.userAgentLabel(s.userAgent) + "  ▾") { menu = "ua" }
            }
        }
        item {
            SettingsSection("Lecture") {
                KitToggle("Épisode suivant automatique", "À la fin d'un épisode, le suivant se lance.", s.autoNext) { model.set(SettingKey.AUTO_NEXT, it) }
                if (model.hasAutoQuality) KitToggle("Qualité auto en direct", "Si l'image coupe, la même chaîne en qualité plus basse (FHD → HD → SD).", s.autoQuality) { model.set(SettingKey.AUTO_QUALITY, it) }
                KitToggle("Mémoire tampon stable", "Pour une connexion faible : démarre un peu plus lentement, coupe moins.", s.stableBuffer) { model.set(SettingKey.STABLE_BUFFER, it) }
                KitToggle("Sous-titres activés", "Sinon, seulement les sous-titres forcés.", s.subtitlesOn) { model.set(SettingKey.SUBTITLES_ON, it) }
                button("Langue audio : " + Choices.languageLabel(s.audioLang) + "  ▾") { menu = "audio" }
                button("Sous-titres : " + Choices.languageLabel(s.subtitleLang) + "  ▾") { menu = "subs" }
                if (model.hasResizeMode) {
                    label("Format d'image")
                    chips { Choices.resizeModes.forEach { (m, text) -> KitChip(text, s.resizeMode == m) { model.setResizeMode(m) } } }
                }
                label("Au lancement")
                chips { Choices.startups.forEach { (v, text) -> KitChip(text, s.startup == v) { model.set(SettingKey.STARTUP, v) } } }
            }
        }
        item {
            SettingsSection("Guide TV") {
                label("Jours de programmes gardés")
                chips { Choices.epgDays.forEach { d -> KitChip("$d j", s.epgDays == d) { model.setEpgDays(d) } } }
                if (active != null) button("Recharger le guide") { model.reloadGuide() }
                button("Effacer le guide enregistré") { clearingGuide = true }
            }
        }
        item {
            var refresh by remember { mutableStateOf(0) }
            val bytes by produceState<Long?>(null, refresh) { value = runCatching { model.cacheBytes() }.getOrNull() }
            SettingsSection("Stockage") {
                label("Cache des images et de TMDB : " + (bytes?.let { "${it / 1_000_000} Mo" } ?: "…"))
                Text("Les téléchargements, la liste et le guide ne sont pas touchés.", color = c.dim, fontSize = 12.sp)
                button("Vider le cache") { scope.launch { runCatching { model.clearCache() }; refresh++ } }
            }
        }
        model.atEnd.forEach { (title, content) -> item { SettingsSection(title, content = content) } }
        item { Text(model.footer, color = c.dim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
    }
    }

    // ---- The windows -------------------------------------------------------------------------------------------------
    listMenu?.let { l ->
        KitMenu(l.name, listOfNotNull(
            if (!l.active) KitOption("Utiliser cette liste") { model.useList(l.key) } else null,
            KitOption("Actualiser") { model.reloadList(l.key) },
            KitOption("Modifier") { model.editList(l.key) },
            if (l.secretsInClear) KitOption("Chiffrer les identifiants") {
                scope.launch { notice = if (model.encryptList(l.key)) "Identifiants chiffrés." else "Le coffre de l'appareil refuse encore : identifiants non chiffrés." }
            } else null,
            KitOption("Supprimer", danger = true) { deleting = l },
        ), { listMenu = null }, if (l.isXtream) "Compte Xtream" else "Playlist M3U")
    }
    deleting?.let { l ->
        KitConfirm("Supprimer « ${l.name} » ?", "Ses chaînes, films, séries, son guide et son historique sont retirés de ${model.deviceWord}.", "Supprimer",
            onConfirm = { model.deleteList(l.key) }, onDismiss = { deleting = null }, danger = true)
    }
    if (clearingGuide) KitConfirm("Effacer le guide TV ?", "Il sera de nouveau téléchargé à la prochaine actualisation.", "Effacer",
        onConfirm = { model.clearGuide() }, onDismiss = { clearingGuide = false }, danger = true)
    when (menu) {
        "ua" -> KitMenu("User-Agent par défaut", Choices.userAgents.map { (v, text) -> KitOption(text, v == s.userAgent) { model.set(SettingKey.USER_AGENT, v) } }, { menu = null },
            "Certains fournisseurs n'acceptent que certains lecteurs.")
        "audio" -> KitMenu("Langue audio", Choices.languages.map { (v, text) -> KitOption(text, v == s.audioLang) { model.set(SettingKey.AUDIO_LANG, v) } }, { menu = null })
        "subs" -> KitMenu("Langue des sous-titres", Choices.languages.map { (v, text) -> KitOption(text, v == s.subtitleLang) { model.set(SettingKey.SUBTITLE_LANG, v) } }, { menu = null })
    }
    if (testing) {
        var run by remember { mutableStateOf(0) }
        val report by produceState<NetReport?>(null, run) { value = null; value = model.networkReport() }
        NetworkReportDialog(report?.checks?.map { NetLine(it.what, it.result, it.level) }, report?.let { it.diagnosis ?: NetworkCheck.ALL_GOOD }, { run++ }, { testing = false })
    }
    pinStep?.let { step ->
        KitPinDialog(when (step) {
            PinStep.Current, PinStep.Remove -> "Code PIN actuel"
            PinStep.New -> "Nouveau code PIN"
            PinStep.ShowHidden -> "Afficher les éléments masqués"
            is PinStep.Confirm -> "Confirme le code PIN"
        }, if (step == PinStep.New) "Quatre chiffres ou plus." else null, pinError, { pinStep = null; pinError = null }) { pin ->
            scope.launch {
                when (step) {
                    PinStep.Current -> if (model.checkPin(pin)) { pinStep = PinStep.New; pinError = null } else pinError = "Code incorrect."
                    PinStep.New -> { pinStep = PinStep.Confirm(pin); pinError = null }
                    is PinStep.Confirm -> if (pin == step.first) { model.setPin(pin); pinStep = null; notice = "Code PIN enregistré." }
                        else { pinStep = PinStep.New; pinError = "Les deux codes ne sont pas les mêmes." }
                    PinStep.Remove -> if (model.checkPin(pin)) { model.setPin(""); pinStep = null; notice = "Code PIN supprimé. Les groupes verrouillés s'ouvrent sans code." } else pinError = "Code incorrect."
                    PinStep.ShowHidden -> if (model.checkPin(pin)) { model.set(SettingKey.SHOW_HIDDEN, true); pinStep = null } else pinError = "Code incorrect."
                }
            }
        }
    }
    notice?.let { n -> KitDialog(n, { notice = null }, buttons = { KitButton("OK", primary = true, modifier = Modifier.fillMaxWidth()) { notice = null } }) }
    LaunchedEffect(s.lists.size) { if (listMenu != null && s.lists.none { it.key == listMenu?.key }) listMenu = null }
}
