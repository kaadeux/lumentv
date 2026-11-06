package com.lumentv.phone

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.lumentv.AppCore
import com.lumentv.core.IoDispatcher
import com.lumentv.core.networkCheck
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.repo.reEncrypted
import com.lumentv.data.repo.secretsInClear
import com.lumentv.data.settings.Keys
import com.lumentv.ui.kit.SettingKey
import com.lumentv.ui.kit.SettingsList
import com.lumentv.ui.kit.SettingsModel
import com.lumentv.ui.kit.SettingsPage
import com.lumentv.ui.kit.SettingsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A section a device adds to the shared settings (Android: updates, backup, the TV's screen rate). */
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
    /** The pictures' cache and TMDB's answers: their size and emptying them. Null: the shared one. */
    val cache: CacheOps? = null,
    /** The connection test's own panel (Android's knows the Wi-Fi signal). Null: the shared one. */
    val onNetworkTest: (() -> Unit)? = null,
    val deviceWord: String = "cet iPhone",
)

class CacheOps(val sizeBytes: suspend () -> Long, val clear: suspend () -> Unit)

/** The shared settings page on the apps built on [AppCore] (TV, phone, iPhone): their data and actions. */
private class CoreSettingsModel(
    private val core: AppCore, private val extras: SettingsExtras, private val edit: (Long, String) -> Unit,
    private val platformBytes: suspend () -> Long, private val platformClear: suspend () -> Unit,
) : SettingsModel {
    private val playlistsFlow = core.playlists.observe()

    @Composable override fun state(): SettingsState {
        val playlists by playlistsFlow.collectAsState(emptyList())
        val busy by core.playlists.busy.collectAsState()
        val settings by core.settings.flow.collectAsState(null)
        val profile by core.profile.state.collectAsState()
        val s = settings
        val active = playlists.firstOrNull { it.id == s?.activePlaylistId } ?: playlists.firstOrNull()
        return SettingsState(
            profile.name, when {
                profile.syncing -> "Synchronisation…"
                profile.error != null -> profile.error
                profile.lastSync > 0 -> "Synchronisé à " + clock(profile.lastSync)
                else -> "Pas encore synchronisé"
            }, profile.error != null,
            playlists.map { p ->
                SettingsList(p.id.toString(), p.name, p.type == PlaylistType.XTREAM, p.id == active?.id, p.channelCount, p.movieCount, p.seriesCount,
                    p.lastRefresh.takeIf { it > 0 }?.let { day(it) }, p.accountInfo, p.refreshHours, p.id in busy, p.lastError?.takeIf { it.isNotBlank() }, p.secretsInClear())
            },
            s?.autoNext ?: true, s?.autoQuality ?: true, s?.bufferMode == "stable", s?.subtitlesOn ?: false,
            s?.audioLang.orEmpty(), s?.subtitleLang.orEmpty(), s?.resizeMode ?: 0, s?.startup ?: "home", s?.defaultUserAgent.orEmpty(),
            s?.epgDays ?: 3, s?.showHidden ?: false, !s?.pinHash.isNullOrEmpty(),
        )
    }

    private fun launch(block: suspend () -> Unit) { core.scope.launch { block() } }
    private suspend fun list(key: String): PlaylistEntity? = key.toLongOrNull()?.let { core.playlists.get(it) }

    override val profileEnabled get() = core.profile.enabled
    override suspend fun signIn(name: String, passphrase: String) = core.profile.signIn(name, passphrase)
    override suspend fun syncNow() { core.profile.syncNow() }
    override fun signOut() = core.profile.signOut()

    override fun addList(isXtream: Boolean) = edit(0, if (isXtream) PlaylistType.XTREAM else PlaylistType.M3U)
    override fun editList(key: String) = launch { list(key)?.let { edit(it.id, it.type) } }
    override fun useList(key: String) = launch { key.toLongOrNull()?.let { core.settings.set(Keys.activePlaylist, it) } }
    override fun reloadList(key: String) = launch { key.toLongOrNull()?.let { if (core.playlists.refresh(it).ok) core.epg.refresh(it) } }
    override fun deleteList(key: String) = launch {
        val id = key.toLongOrNull() ?: return@launch
        val next = core.playlists.all().firstOrNull { it.id != id }
        if (core.settings.current().activePlaylistId == id && next != null) core.settings.set(Keys.activePlaylist, next.id)
        core.playlists.delete(id)
        core.resolver.forget()
    }
    override fun setReloadHours(key: String, hours: Int) = launch { list(key)?.let { core.playlists.save(it.copy(refreshHours = hours)) } }
    override suspend fun encryptList(key: String): Boolean {
        val again = list(key)?.reEncrypted() ?: return false
        core.playlists.save(again)
        return !again.secretsInClear()
    }

    override fun privacy() = extras.privacy()
    override val privacyNote get() = extras.privacyNote ?: com.lumentv.core.Privacy.NOTE
    override suspend fun networkReport() = core.networkCheck()
    override val onNetworkTest get() = extras.onNetworkTest

    override fun set(key: SettingKey, on: Boolean) = launch {
        when (key) {
            SettingKey.AUTO_NEXT -> core.settings.set(Keys.autoNext, on)
            SettingKey.AUTO_QUALITY -> core.settings.set(Keys.autoQuality, on)
            SettingKey.STABLE_BUFFER -> core.settings.set(Keys.buffer, if (on) "stable" else "normal")
            SettingKey.SUBTITLES_ON -> core.settings.set(Keys.subsOn, on)
            SettingKey.SHOW_HIDDEN -> core.settings.set(Keys.showHidden, on)
            else -> Unit
        }
    }
    override fun set(key: SettingKey, value: String) = launch {
        when (key) {
            SettingKey.AUDIO_LANG -> core.settings.set(Keys.audioLang, value)
            SettingKey.SUBTITLE_LANG -> core.settings.set(Keys.subLang, value)
            SettingKey.STARTUP -> core.settings.set(Keys.startup, value)
            SettingKey.USER_AGENT -> core.settings.set(Keys.userAgent, value)
            else -> Unit
        }
    }
    override fun setEpgDays(days: Int) = launch { core.settings.set(Keys.epgDays, days) }
    override fun setResizeMode(mode: Int) = launch { core.settings.set(Keys.resize, mode) }
    override fun reloadGuide() = launch { (core.playlists.get(core.settings.current().activePlaylistId) ?: core.playlists.all().firstOrNull())?.let { core.epg.refresh(it.id) } }
    override fun clearGuide() = launch { core.epg.clearAll() }

    override val parentalHint get() = extras.parentalHint
    override suspend fun checkPin(pin: String) = core.settings.checkPin(pin)
    override suspend fun setPin(pin: String) { core.settings.setPin(pin) }

    override suspend fun cacheBytes() = withContext(IoDispatcher) { extras.cache?.sizeBytes?.invoke() ?: platformBytes() }
    override suspend fun clearCache() = withContext(IoDispatcher) { extras.cache?.clear?.invoke() ?: platformClear(); core.logos.forget() }

    override val deviceWord get() = extras.deviceWord
    override val footer get() = extras.footer
    override val afterLists get() = extras.afterLists.map { it.title to it.content }
    override val atEnd get() = extras.atEnd.map { it.title to it.content }
}

/**
 * The settings page for an app built on [AppCore]: lists are added and changed on the device's own screen
 * ([onEditList]). [pinHost]: false when the app already shows the PIN windows ([PinHost]) itself.
 */
@Composable
fun SharedSettings(core: AppCore, onEditList: (id: Long, type: String) -> Unit, extras: SettingsExtras, maxWidth: Dp = Dp.Unspecified, pinHost: Boolean = true) = LumenTheme {
    val context = coil3.compose.LocalPlatformContext.current
    val model = remember(core, extras) {
        CoreSettingsModel(core, extras, onEditList, { cacheBytes(context) }, { clearCaches(context) })
    }
    SettingsPage(model, maxWidth = maxWidth, listState = rememberList("settings"))
    if (pinHost) PinHost(core)
}

/** The shared pieces a device uses in its [SettingsExtra]s, so they look like the rest. */
@Composable
fun SettingsButton(text: String, primary: Boolean = false, onClick: () -> Unit) = PhoneButton(text, primary, Modifier.fillMaxWidth(), onClick)

@Composable
fun SettingsToggle(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) = Toggle(title, subtitle, on, onChange)

@Composable
fun SettingsNote(text: String) = androidx.compose.material3.Text(text, color = Palette.TextDim, fontSize = 14.sp)
