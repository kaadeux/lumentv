package com.lumentv.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.lumentv.AppCore
import com.lumentv.data.db.ChannelRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * The parental control, as on Android: groups locked by the PIN (asked once per group until the app is closed),
 * whatever the way to a channel is (TV, search, favourites, guide, sport).
 */
internal object Parental {
    class Ask(val title: String, val result: CompletableDeferred<Boolean>)

    /** The PIN asked now, shown by [PinHost]. */
    var ask by mutableStateOf<Ask?>(null)
    /** A short message (no PIN yet…), shown by [PinHost]. */
    var notice by mutableStateOf<String?>(null)
    private val unlocked = mutableStateMapOf<String, Boolean>()

    private fun key(pid: Long, group: String) = "$pid\u0001$group"
    fun isUnlocked(pid: Long, group: String) = unlocked[key(pid, group)] == true
    fun relock(pid: Long, group: String) { unlocked.remove(key(pid, group)) }

    /** Asks the PIN; true when it was right (or there is none). */
    suspend fun check(core: AppCore, title: String): Boolean {
        if (core.settings.current().pinHash.isEmpty()) return true
        val a = Ask(title, CompletableDeferred())
        ask = a
        return a.result.await()
    }

    suspend fun unlockGroup(core: AppCore, pid: Long, group: String): Boolean {
        if (isUnlocked(pid, group)) return true
        val ok = check(core, "Groupe verrouillé « $group »")
        if (ok) unlocked[key(pid, group)] = true
        return ok
    }

    /** False when the channel's group is locked and the PIN was not given. */
    suspend fun allow(core: AppCore, c: ChannelRow): Boolean {
        val pid = c.ch.playlistId
        if (isUnlocked(pid, c.ch.groupName) || c.ch.groupName !in core.library.lockedGroups(pid)) return true
        return unlockGroup(core, pid, c.ch.groupName)
    }
}

/** The PIN asked by [Parental.check] and its notices, over every page. */
@Composable
internal fun PinHost(core: AppCore) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Parental.ask?.let { a ->
        com.lumentv.ui.kit.KitPinDialog(a.title, "Saisis le code PIN pour continuer.", error,
            onDismiss = { error = null; Parental.ask = null; a.result.complete(false) }) { pin ->
            scope.launch {
                if (core.settings.checkPin(pin)) { error = null; Parental.ask = null; a.result.complete(true) }
                else error = "Code incorrect."
            }
        }
    }
    Parental.notice?.let { n ->
        com.lumentv.ui.kit.KitDialog(n, onDismiss = { Parental.notice = null },
            buttons = { com.lumentv.ui.kit.KitButton("OK", primary = true, modifier = Modifier.fillMaxWidth()) { Parental.notice = null } })
    }
}

