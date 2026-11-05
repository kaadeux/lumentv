package com.lumentv.data.xtream

import com.lumentv.sync.ProfileKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * "Who is watching on this subscription right now", shared through the sync server between Lumen TV devices.
 * Providers' own connection counters are often stale (always 0), so the devices tell each other instead.
 * The subscription is identified by a hash that includes its password: the server never sees the account.
 */
class Presence(http: OkHttpClient, baseUrl: String, private val deviceId: String, private val deviceName: String) {
    private val http = http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    val enabled = base.isNotEmpty()

    @Serializable private class Other(val name: String, val until: Long)
    @Serializable private class Others(val others: List<Other> = emptyList())

    /** Names of the other devices playing on this subscription (empty when unknown). */
    suspend fun others(key: String): List<String> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        runCatching {
            val url = "$base/v1/presence/$key".toHttpUrl().newBuilder().addQueryParameter("device", deviceId).build()
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) emptyList() else json.decodeFromString<Others>(r.body!!.string()).others.map { it.name }.distinct()
            }
        }.getOrDefault(emptyList())
    }

    /** "Still playing" — repeated every [BEAT_MS] while a stream plays. */
    suspend fun beat(key: String) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        runCatching {
            val body = """{"device":${q(deviceId)},"name":${q(deviceName)},"ttl":${BEAT_MS / 1000 * 3}}"""
            http.newCall(Request.Builder().url("$base/v1/presence/$key").put(body.toRequestBody("application/json".toMediaType())).build()).execute().close()
        }
    }

    suspend fun leave(key: String) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        runCatching {
            val url = "$base/v1/presence/$key".toHttpUrl().newBuilder().addQueryParameter("device", deviceId).build()
            http.newCall(Request.Builder().url(url).delete().build()).execute().close()
        }
    }

    private fun q(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

    companion object {
        const val BEAT_MS = 30_000L

        /** Same value on every device using this account; useless to anyone who does not know the password. */
        fun key(type: String, source: String, username: String, password: String): String =
            ProfileKeys.hex(ProfileKeys.sha256("lumentv-presence|$type|${source.trim().trimEnd('/').lowercase()}|${username.trim()}|$password".toByteArray()))

        fun warning(devices: List<String>): String {
            val who = devices.joinToString(", ") { "« $it »" }
            return "Votre abonnement est déjà en cours de lecture sur $who. Il n'autorise qu'un écran : " +
                "si vous lancez la lecture ici, l'autre appareil risque d'être coupé, ou ce flux d'être refusé."
        }
    }
}
