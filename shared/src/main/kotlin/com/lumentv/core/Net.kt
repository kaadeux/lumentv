package com.lumentv.core

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Retries a failed GET a couple of times: on "too many requests" (429) and a busy or restarting server (502, 503, 504),
 * or a dropped connection. The waits grow (about 0.5 s, then 1.5 s) with some randomness, so many clients don't come
 * back at the same instant; a server's own "Retry-After" is followed when it is short. Only for metadata APIs (TMDB,
 * sports): never for live streams, which have their own reconnection.
 */
class RetryInterceptor(
    private val retries: Int = 2,
    private val baseMs: Long = 500,
    private val maxWaitMs: Long = 4_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.method != "GET") return chain.proceed(req)
        var attempt = 0
        while (true) {
            val response = try {
                chain.proceed(req)
            } catch (e: IOException) {
                if (attempt >= retries || chain.call().isCanceled()) throw e
                null
            }
            if (response != null && (response.code !in RETRIED || attempt >= retries)) return response
            val wait = response?.let { retryAfterMs(it.header("Retry-After")) } ?: backoff(attempt)
            // The server asks for a long pause: its answer is given as is (the callers keep their last good data).
            if (wait > maxWaitMs && response != null) return response
            response?.close()
            sleep(wait.coerceAtMost(maxWaitMs))
            attempt++
        }
    }

    /** About [baseMs] × 3^attempt, ±25 %. */
    fun backoff(attempt: Int): Long {
        var d = baseMs; repeat(attempt) { d *= 3 }
        return (d * (0.75 + Random.nextDouble() * 0.5)).toLong().coerceAtMost(maxWaitMs)
    }

    companion object {
        val RETRIED = setOf(429, 502, 503, 504)

        /** "Retry-After: 3" → 3000 ms; a date or nothing → null (then the usual backoff). */
        fun retryAfterMs(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
    }
}

/**
 * Light counters of the app's requests, per host: how many, from the disk cache, failed, and the total time. Almost
 * free (a few atomic counters); written to the log (tag "LumenNet") only when asked, see [summary].
 */
object NetStats {
    class Host { val calls = AtomicLong(); val cached = AtomicLong(); val errors = AtomicLong(); val millis = AtomicLong() }
    val hosts = ConcurrentHashMap<String, Host>()

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val h = hosts.getOrPut(req.url.host) { Host() }
        val start = System.nanoTime()
        h.calls.incrementAndGet()
        try {
            chain.proceed(req).also { r ->
                if (r.networkResponse == null && r.cacheResponse != null) h.cached.incrementAndGet()
                if (!r.isSuccessful && r.code != 304) h.errors.incrementAndGet()
            }
        } catch (e: IOException) {
            h.errors.incrementAndGet(); throw e
        } finally {
            h.millis.addAndGet((System.nanoTime() - start) / 1_000_000)
        }
    }

    /** One line per host: "api.themoviedb.org 42 calls, 30 cached, 0 errors, avg 85 ms". */
    fun summary(): String = hosts.entries.sortedByDescending { it.value.calls.get() }.joinToString("\n") { (host, h) ->
        val n = h.calls.get()
        "$host $n calls, ${h.cached.get()} cached, ${h.errors.get()} errors, avg ${if (n == 0L) 0 else h.millis.get() / n} ms"
    }
}

/**
 * Runs [load] once for a key even when several callers ask at the same time: the others wait for and share its
 * answer (two rows asking TMDB for the same title at once make one request).
 */
class InFlight<K : Any, V> {
    private val running = ConcurrentHashMap<K, java.util.concurrent.CompletableFuture<V>>()

    fun get(key: K, load: () -> V): V {
        val mine = java.util.concurrent.CompletableFuture<V>()
        val other = running.putIfAbsent(key, mine)
        if (other != null) return try { other.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
        try {
            return load().also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e); throw e
        } finally {
            running.remove(key, mine)
        }
    }
}
