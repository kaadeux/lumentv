package com.lumentv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Where the recap, the intro and the credits are in an episode (ms); null when unknown. */
data class Segments(val recap: LongRange?, val intro: LongRange?, val credits: LongRange?) {
    val known get() = recap != null || intro != null
}

/**
 * Recap, intro and credits of an episode from TheIntroDB (free community database, keyed by the series' TMDB id),
 * so the players can offer "Passer le récap" and "Passer l'intro" at the right moments. One small request per episode.
 */
class IntroDb(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val cache = HashMap<String, Segments>()

    suspend fun segments(tmdb: String, season: Int, episode: Int): Segments? = withContext(Dispatchers.IO) {
        val key = "$tmdb/$season/$episode"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        runCatching {
            val req = Request.Builder().url("https://api.theintrodb.org/v3/media?tmdb_id=$tmdb&season=$season&episode=$episode").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                parse(r.body!!.string())?.also { synchronized(cache) { cache[key] = it } }
            }
        }.getOrNull()
    }

    companion object {
        fun parse(body: String): Segments? {
            val o = Json.parseToJsonElement(body) as? JsonObject ?: return null
            // A recap or intro needs a real end (some entries have none, or are empty: 0 to 0); credits may run to the end.
            fun seg(name: String, openEnd: Boolean): LongRange? {
                val first = (o[name] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
                val start = (first["start_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
                val end = (first["end_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: if (openEnd) Long.MAX_VALUE else return null
                return if (end - start >= 5_000) start until end else null
            }
            return Segments(seg("recap", false), seg("intro", false), seg("credits", true))
        }
    }
}
