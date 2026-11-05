package com.lumentv.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The passphrase does not open this profile (or another family already uses this name). */
class WrongPassphraseException : IOException("Phrase secrète incorrecte pour ce profil.")

/** Talks to the sync server (sync-server/ in this repository). Only encrypted data crosses the network. */
class ProfileSync(private val http: OkHttpClient, baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable private class Remote(val version: Int, val data: String = "")
    @Serializable private class Put(val baseVersion: Int, val data: String)

    class Result(val doc: ProfileDoc, val version: Int)

    /** Downloads the profile, or null when it does not exist yet. */
    suspend fun pull(keys: ProfileKeys): Result? = withContext(Dispatchers.IO) {
        get(keys)?.let { Result(decode(keys, it.data), it.version) }
    }

    /**
     * Merges [local] with the server copy and uploads the result when it differs. Retries when another device wrote
     * in between. @return the merged profile, to apply on this device.
     */
    suspend fun sync(keys: ProfileKeys, local: ProfileDoc): Result = withContext(Dispatchers.IO) {
        var remote = get(keys)
        repeat(5) {
            val remoteDoc = remote?.let { decode(keys, it.data) }
            val merged = ProfileMerge.prune(ProfileMerge.merge(local, remoteDoc))
            if (remote != null && merged == remoteDoc) return@withContext Result(merged, remote!!.version)
            val base = remote?.version ?: 0
            val body = json.encodeToString(Put(base, ProfileCrypto.seal(keys, json.encodeToString(merged))))
            val req = request(keys).put(body.toRequestBody(JSON)).build()
            http.newCall(req).execute().use { r ->
                when (r.code) {
                    200 -> return@withContext Result(merged, json.decodeFromString<Remote>(r.body!!.string()).version)
                    409 -> remote = json.decodeFromString<Remote>(r.body!!.string())
                    403 -> throw WrongPassphraseException()
                    else -> throw IOException("Serveur de profils : HTTP ${r.code}")
                }
            }
        }
        throw IOException("Trop de modifications simultanées, réessayez.")
    }

    /** Deletes the profile from the server (devices keep their own data). */
    suspend fun delete(keys: ProfileKeys) = withContext(Dispatchers.IO) {
        http.newCall(request(keys).delete().build()).execute().use { r ->
            if (r.code == 403) throw WrongPassphraseException()
            if (r.code != 204) throw IOException("Serveur de profils : HTTP ${r.code}")
        }
    }

    private fun get(keys: ProfileKeys): Remote? =
        http.newCall(request(keys).get().build()).execute().use { r ->
            when (r.code) {
                200 -> json.decodeFromString<Remote>(r.body!!.string())
                404 -> null
                403 -> throw WrongPassphraseException()
                else -> throw IOException("Serveur de profils : HTTP ${r.code}")
            }
        }

    private fun decode(keys: ProfileKeys, data: String): ProfileDoc =
        try { json.decodeFromString(ProfileCrypto.open(keys, data)) }
        catch (e: com.lumentv.core.CryptoException) { throw WrongPassphraseException() }

    private fun request(keys: ProfileKeys) =
        Request.Builder().url("$base/v1/profile/${keys.id}").header("Authorization", "Bearer ${keys.token}")

    private companion object { val JSON = "application/json".toMediaType() }
}
