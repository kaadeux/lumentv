package com.lumentv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetTest {
    /** A client whose "server" answers [codes] in turn (no network), retrying with [waits] recording the pauses. */
    private fun client(codes: List<Int>, waits: MutableList<Long>, calls: AtomicInteger, retryAfter: String? = null) = OkHttpClient.Builder()
        .addInterceptor(RetryInterceptor(sleep = { waits += it }))
        .addInterceptor { chain ->
            val code = codes[minOf(calls.getAndIncrement(), codes.lastIndex)]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .apply { if (retryAfter != null) header("Retry-After", retryAfter) }.body("ok".toResponseBody()).build()
        }.build()

    private fun get(c: OkHttpClient) = c.newCall(Request.Builder().url("https://example.test/a").build()).execute().use { it.code }

    @Test fun retriesBusyServerThenSucceeds() {
        val waits = mutableListOf<Long>(); val calls = AtomicInteger()
        assertEquals(200, get(client(listOf(503, 429, 200), waits, calls)))
        assertEquals(3, calls.get())
        assertEquals(2, waits.size)
        assertTrue("growing waits $waits", waits[0] in 375L..625L && waits[1] in 1125L..1875L)
    }

    @Test fun givesUpAfterTwoRetries() {
        val waits = mutableListOf<Long>(); val calls = AtomicInteger()
        assertEquals(503, get(client(listOf(503), waits, calls)))
        assertEquals(3, calls.get())
    }

    @Test fun clientErrorsAreNotRetried() {
        val waits = mutableListOf<Long>(); val calls = AtomicInteger()
        assertEquals(404, get(client(listOf(404), waits, calls)))
        assertEquals(1, calls.get())
    }

    @Test fun followsShortRetryAfterAndStopsOnLongOne() {
        val waits = mutableListOf<Long>(); val calls = AtomicInteger()
        assertEquals(200, get(client(listOf(429, 200), waits, calls, retryAfter = "2")))
        assertEquals(listOf(2000L), waits)
        val waits2 = mutableListOf<Long>(); val calls2 = AtomicInteger()
        assertEquals(429, get(client(listOf(429, 200), waits2, calls2, retryAfter = "120")))
        assertEquals(1, calls2.get())
    }

    @Test fun retryAfterParsing() {
        assertEquals(3000L, RetryInterceptor.retryAfterMs(" 3 "))
        assertEquals(null, RetryInterceptor.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(null, RetryInterceptor.retryAfterMs(null))
    }

    @Test fun concurrentSameKeyLoadsOnce() {
        val inFlight = InFlight<String, Int>()
        val loads = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        val results = (1..6).map { pool.submit<Int> { start.await(); inFlight.get("k") { loads.incrementAndGet(); Thread.sleep(150); 42 } } }
        start.countDown()
        assertEquals(List(6) { 42 }, results.map { it.get(5, TimeUnit.SECONDS) })
        assertEquals(1, loads.get())
        // Once finished, the next ask loads again (no stale answer kept).
        assertEquals(7, inFlight.get("k") { 7 })
        pool.shutdown()
    }
}
