package com.lumentv.data.xtream

import com.lumentv.sync.ProfileKeys
import com.lumentv.core.Http
import com.lumentv.core.HttpRequest
import com.lumentv.core.IoDispatcher
import com.lumentv.core.query
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * "Who is watching on this subscription right now", shared through the sync server between Lumen TV devices.
 * Providers' own connection counters are often stale (always 0), so the devices tell each other instead.
 * The subscription is identified by a hash that includes its password: the server never sees the account.
 */
class Presence(private val http: Http, baseUrl: String, private val deviceId: String, private val deviceName: String) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    val enabled = base.isNotEmpty()

    @Serializable private class Other(val name: String, val until: Long)
    @Serializable private class Others(val others: List<Other> = emptyList())

    /** Names of the other devices playing on this subscription (empty when unknown). */
    suspend fun others(key: String): List<String> = withContext(IoDispatcher) {
        if (!enabled) return@withContext emptyList()
        runCatching {
            http.execute(HttpRequest("$base/v1/presence/$key?${query("device" to deviceId)}", timeoutMs = TIMEOUT_MS)).use { r ->
                if (!r.isSuccessful) emptyList() else json.decodeFromString<Others>(r.text()).others.map { it.name }.distinct()
            }
        }.getOrDefault(emptyList())
    }

    /** "Still playing" — repeated every [BEAT_MS] while a stream plays. */
    suspend fun beat(key: String) = withContext(IoDispatcher) {
        if (!enabled) return@withContext
        runCatching {
            val body = """{"device":${q(deviceId)},"name":${q(deviceName)},"ttl":${BEAT_MS / 1000 * 3}}"""
            http.execute(HttpRequest("$base/v1/presence/$key", "PUT", body = body.encodeToByteArray(), contentType = "application/json", timeoutMs = TIMEOUT_MS)).close()
        }
    }

    suspend fun leave(key: String) = withContext(IoDispatcher) {
        if (!enabled) return@withContext
        runCatching {
            http.execute(HttpRequest("$base/v1/presence/$key?${query("device" to deviceId)}", "DELETE", timeoutMs = TIMEOUT_MS)).close()
        }
    }

    private fun q(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

    companion object {
        const val BEAT_MS = 30_000L
        /** A presence check never holds the player up long. */
        private const val TIMEOUT_MS = 4_000L

        /** Same value on every device using this account; useless to anyone who does not know the password. */
        fun key(type: String, source: String, username: String, password: String): String =
            ProfileKeys.hex(ProfileKeys.sha256("lumentv-presence|$type|${source.trim().trimEnd('/').lowercase()}|${username.trim()}|$password".encodeToByteArray()))

        fun warning(devices: List<String>): String {
            val who = devices.joinToString(", ") { "« $it »" }
            return "Votre abonnement est déjà en cours de lecture sur $who. Il n'autorise qu'un écran : " +
                "si vous lancez la lecture ici, l'autre appareil risque d'être coupé, ou ce flux d'être refusé."
        }
    }
}
