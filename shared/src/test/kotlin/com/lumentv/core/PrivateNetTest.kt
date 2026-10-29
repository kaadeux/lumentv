package com.lumentv.core

import com.lumentv.core.PrivateNet.private
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Live (NET_LIVE=1): encrypted DNS answers, and a server that speaks HTTPS is moved to it. */
class PrivateNetTest {
    @Test fun dnsAndUpgrade() {
        assumeTrue(System.getenv("NET_LIVE") == "1")
        PrivateNet.init(kotlin.io.path.createTempFile().toFile())
        assertTrue(PrivateNet.dns.lookup("example.com").isNotEmpty())
        assertEquals(0, PrivateNet.dnsFallbacks)
        val http = OkHttpClient.Builder().private().build()
        // httpbin answers JSON on any path, over HTTP and HTTPS.
        http.newCall(Request.Builder().url("http://httpbin.org/anything/player_api.php").build()).execute().use { r ->
            assertEquals("https", r.request.url.scheme)
        }
        // Then even a stream address of that host goes over HTTPS.
        http.newCall(Request.Builder().url("http://httpbin.org/anything/movie/1.mkv").build()).execute().use { r ->
            assertEquals("https", r.request.url.scheme)
        }
        // Refused over HTTPS: asked again over plain HTTP, as before the upgrade.
        http.newCall(Request.Builder().url("http://httpbin.org/status/403").build()).execute().use { r ->
            assertEquals("http", r.request.url.scheme)
        }
        assertTrue("httpbin.org" in PrivateNet.secureHosts)
        PrivateNet.cleartextHosts.clear()
        assertTrue(PrivateNet.cleartextHosts.isEmpty())
        println(PrivateNet.summary())
    }
}
