package com.lumentv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

data class Settings(
    val activePlaylistId: Long = 0,
    /** "home" or "last": what the app opens at launch. */
    val startup: String = "home",
    val lastChannelId: Long = 0,
    /** "normal" or "stable" (longer buffer for weak connections). */
    val bufferMode: String = "normal",
    /** 0 = fit, 1 = zoom (crop), 2 = stretch. */
    val resizeMode: Int = 0,
    val audioLang: String = "",
    val subtitleLang: String = "",
    val subtitlesOn: Boolean = false,
    val epgDays: Int = 3,
    /** "provider", "az" or "za". */
    val sort: String = "provider",
    val showHidden: Boolean = false,
    val autoNext: Boolean = true,
    val pinHash: String = "",
    val defaultUserAgent: String = "",
    val sportIds: Set<String> = emptySet(),
    val sportCompetitions: List<String> = emptyList(),
    val sportTeams: List<String> = emptyList(),
    val sportDays: Int = 3,
)

object Keys {
    val activePlaylist = longPreferencesKey("active_playlist")
    val startup = stringPreferencesKey("startup")
    val lastChannel = longPreferencesKey("last_channel")
    val buffer = stringPreferencesKey("buffer")
    val resize = intPreferencesKey("resize")
    val audioLang = stringPreferencesKey("audio_lang")
    val subLang = stringPreferencesKey("sub_lang")
    val subsOn = booleanPreferencesKey("subs_on")
    val epgDays = intPreferencesKey("epg_days")
    val sort = stringPreferencesKey("sort")
    val showHidden = booleanPreferencesKey("show_hidden")
    val autoNext = booleanPreferencesKey("auto_next")
    val pin = stringPreferencesKey("pin_hash")
    val userAgent = stringPreferencesKey("user_agent")
    val sportIds = stringSetPreferencesKey("sport_ids")
    val sportComps = stringPreferencesKey("sport_competitions")
    val sportTeams = stringPreferencesKey("sport_teams")
    val sportDays = intPreferencesKey("sport_days")
}

class SettingsStore(private val context: Context) {
    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            activePlaylistId = p[Keys.activePlaylist] ?: 0,
            startup = p[Keys.startup] ?: "home",
            lastChannelId = p[Keys.lastChannel] ?: 0,
            bufferMode = p[Keys.buffer] ?: "normal",
            resizeMode = p[Keys.resize] ?: 0,
            audioLang = p[Keys.audioLang] ?: "",
            subtitleLang = p[Keys.subLang] ?: "",
            subtitlesOn = p[Keys.subsOn] ?: false,
            epgDays = p[Keys.epgDays] ?: 3,
            sort = p[Keys.sort] ?: "provider",
            showHidden = p[Keys.showHidden] ?: false,
            autoNext = p[Keys.autoNext] ?: true,
            pinHash = p[Keys.pin] ?: "",
            defaultUserAgent = p[Keys.userAgent] ?: "",
            sportIds = p[Keys.sportIds] ?: emptySet(),
            sportCompetitions = p[Keys.sportComps]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
            sportTeams = p[Keys.sportTeams]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
            sportDays = p[Keys.sportDays] ?: 3,
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setSports(s: com.lumentv.data.sports.SportPrefs) {
        context.dataStore.edit {
            it[Keys.sportIds] = s.sports
            it[Keys.sportComps] = s.competitions.joinToString("\n")
            it[Keys.sportTeams] = s.teams.joinToString("\n")
            it[Keys.sportDays] = s.days
        }
    }

    suspend fun setPin(pin: String) = set(Keys.pin, if (pin.isEmpty()) "" else hashPin(pin))

    suspend fun checkPin(pin: String): Boolean {
        val h = current().pinHash
        return h.isEmpty() || h == hashPin(pin)
    }

    /** All values as strings, for the backup file. */
    suspend fun export(): Map<String, String> = context.dataStore.data.first().asMap()
        .filterKeys { it.name != Keys.pin.name }
        .entries.associate { it.key.name to it.value.toString() }

    suspend fun import(values: Map<String, String>) {
        context.dataStore.edit { p ->
            values.forEach { (k, v) ->
                when (k) {
                    Keys.startup.name, Keys.buffer.name, Keys.audioLang.name, Keys.subLang.name, Keys.sort.name, Keys.userAgent.name ->
                        p[stringPreferencesKey(k)] = v
                    Keys.resize.name, Keys.epgDays.name -> v.toIntOrNull()?.let { p[intPreferencesKey(k)] = it }
                    Keys.subsOn.name, Keys.showHidden.name, Keys.autoNext.name -> p[booleanPreferencesKey(k)] = v == "true"
                }
            }
        }
    }

    /** The settings a profile shares between devices (not the device-specific ones: start screen, buffer, PIN…). */
    suspend fun syncValues(): Map<String, String> {
        val p = context.dataStore.data.first()
        return buildMap {
            p[Keys.audioLang]?.let { put(Keys.audioLang.name, it) }
            p[Keys.subLang]?.let { put(Keys.subLang.name, it) }
            p[Keys.subsOn]?.let { put(Keys.subsOn.name, it.toString()) }
            p[Keys.autoNext]?.let { put(Keys.autoNext.name, it.toString()) }
            p[Keys.sort]?.let { put(Keys.sort.name, it) }
            p[Keys.epgDays]?.let { put(Keys.epgDays.name, it.toString()) }
            p[Keys.sportIds]?.let { put(Keys.sportIds.name, it.sorted().joinToString("\n")) }
            p[Keys.sportComps]?.let { put(Keys.sportComps.name, it) }
            p[Keys.sportTeams]?.let { put(Keys.sportTeams.name, it) }
            p[Keys.sportDays]?.let { put(Keys.sportDays.name, it.toString()) }
        }
    }

    suspend fun applySync(values: Map<String, String>) {
        if (values == syncValues()) return
        context.dataStore.edit { p ->
            values.forEach { (k, v) ->
                when (k) {
                    Keys.audioLang.name, Keys.subLang.name, Keys.sort.name, Keys.sportComps.name, Keys.sportTeams.name -> p[stringPreferencesKey(k)] = v
                    Keys.subsOn.name, Keys.autoNext.name -> p[booleanPreferencesKey(k)] = v == "true"
                    Keys.epgDays.name, Keys.sportDays.name -> v.toIntOrNull()?.let { p[intPreferencesKey(k)] = it }
                    Keys.sportIds.name -> p[Keys.sportIds] = v.split('\n').filter { it.isNotBlank() }.toSet()
                }
            }
        }
    }

    private fun hashPin(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("lumen:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
}
