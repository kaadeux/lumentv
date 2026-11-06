package com.lumentv.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

/** On this PC only, as on the other devices (never in the online profile). */
@Serializable
private data class ParentalData(
    val pinHash: String = "",
    val showHidden: Boolean = false,
    /** List key → hidden items: "g:" group, "c:" channel, "m:" film category, "s:" series category. */
    val hidden: Map<String, Set<String>> = emptyMap(),
    /** List key → groups locked by the PIN. */
    val locked: Map<String, Set<String>> = emptyMap(),
)

/**
 * The parental control on the PC, as on the TV, the phone and the iPhone: groups locked by the PIN (asked once per group
 * until the app is closed), hidden channels, groups and categories.
 */
class PcParental(private val app: AppState) {
    class Ask(val title: String, val then: () -> Unit)

    enum class Kind(val prefix: String) { GROUP("g:"), CHANNEL("c:"), MOVIE_CATEGORY("m:"), SERIES_CATEGORY("s:") }

    private val file = File(Store.dir, "parental.json")
    private var data by mutableStateOf(load())
    private var unlocked by mutableStateOf(setOf<String>())

    /** The PIN asked now, shown by the window's host. */
    var ask by mutableStateOf<Ask?>(null)

    private fun load() = runCatching { Store.json.decodeFromString<ParentalData>(file.readText()) }.getOrDefault(ParentalData())
    private fun change(new: ParentalData) { data = new; runCatching { file.writeText(Store.json.encodeToString(new)) } }
    private val list get() = app.activeKeyOrFirst.orEmpty()

    val hasPin get() = data.pinHash.isNotEmpty()
    val showHidden get() = data.showHidden
    fun setShowHidden(on: Boolean) = change(data.copy(showHidden = on))

    private fun hash(pin: String) = MessageDigest.getInstance("SHA-256").digest("lumen:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    fun checkPin(pin: String) = !hasPin || hash(pin) == data.pinHash
    fun setPin(pin: String) = change(data.copy(pinHash = if (pin.isEmpty()) "" else hash(pin)))

    /** What this list hides; empty when the setting shows it all. */
    fun hiddenNow(): Set<String> = if (data.showHidden) emptySet() else data.hidden[list].orEmpty()
    fun isHidden(kind: Kind, id: String) = kind.prefix + id in data.hidden[list].orEmpty()
    fun setHidden(kind: Kind, id: String, on: Boolean) {
        val cur = data.hidden[list].orEmpty()
        change(data.copy(hidden = data.hidden + (list to if (on) cur + (kind.prefix + id) else cur - (kind.prefix + id))))
    }

    fun isLocked(group: String) = group in data.locked[list].orEmpty()
    fun isOpen(group: String) = !hasPin || !isLocked(group) || (list + group) in unlocked
    fun setLocked(group: String, on: Boolean) {
        val cur = data.locked[list].orEmpty()
        change(data.copy(locked = data.locked + (list to if (on) cur + group else cur - group)))
        if (on) unlocked = unlocked - (list + group)
    }

    /** [then] at once, or after the PIN when [group] is locked. */
    fun guard(group: String, then: () -> Unit) {
        if (isOpen(group)) then() else ask = Ask("Groupe verrouillé « $group »") { unlocked = unlocked + (list + group); then() }
    }

    /** [then] after the PIN (when there is one). */
    fun confirm(title: String, then: () -> Unit) { if (hasPin) ask = Ask(title, then) else then() }
}
