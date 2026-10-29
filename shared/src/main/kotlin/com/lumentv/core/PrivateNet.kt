package com.lumentv.core

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * Keeps what is watched away from the internet provider, on the TV and the PC:
 * - names are looked up over HTTPS at Cloudflare (1.1.1.1), not at the provider's DNS;
 * - a server reached over plain HTTP is moved to HTTPS when it answers there (tried once on its player_api.php, the
 *   answer kept in [file]); the addresses, which hold the login and what is played, are then encrypted.
 * Servers still reached in clear (one that refuses HTTPS, or a stream redirected to a plain HTTP server) are listed
 * in [cleartextHosts], for the settings screen.
 */
object PrivateNet {
    private const val RETRY_REFUSED_MS = 24 * 3_600_000L

    private var file: File? = null
    /** host → time of the decision: positive = HTTPS works, negative = refused. */
    private val decisions = ConcurrentHashMap<String, Long>()
    /** Hosts the app still had to reach over plain HTTP since it started. */
    val cleartextHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile var dnsFallbacks = 0; private set

    /** Loads the HTTPS decisions kept from earlier runs (host names only). Call once at start-up. */
    fun init(store: File) {
        file = store
        runCatching {
            store.takeIf { it.exists() }?.readLines()?.forEach { l ->
                val (h, t) = l.split(' ').takeIf { it.size == 2 } ?: return@forEach
                t.toLongOrNull()?.let { decisions[h] = it }
            }
        }
    }

    /** Hosts moved to HTTPS. */
    val secureHosts: List<String> get() = decisions.filterValues { it > 0 }.keys.sorted()

    /** For the settings: what the internet provider can still see. */
    fun summary(): Pair<String, String> {
        val clear = cleartextHosts.sorted()
        val title = when {
            clear.isNotEmpty() -> "Partielle"
            secureHosts.isNotEmpty() -> "Protégée"
            else -> "DNS chiffré"
        }
        val detail = listOfNotNull(
            if (dnsFallbacks > 0) "DNS chiffré injoignable ($dnsFallbacks fois) : DNS de la box utilisé" else "Noms des sites chiffrés (Cloudflare)",
            secureHosts.takeIf { it.isNotEmpty() }?.let { "HTTPS : ${it.joinToString(", ")}" },
            clear.takeIf { it.isNotEmpty() }?.let { "en clair, sans HTTPS chez le fournisseur (un VPN seul le cacherait) : ${it.joinToString(", ")}" },
            if (secureHosts.isEmpty() && clear.isEmpty()) "HTTPS vérifié à la prochaine connexion au fournisseur" else null,
        ).joinToString(" · ")
        return title to detail
    }

    /** Adds encrypted DNS and the HTTPS upgrade to a client builder. */
    fun OkHttpClient.Builder.private(): OkHttpClient.Builder = dns(dns).addInterceptor(Upgrade).addNetworkInterceptor(Watch)

    private val doh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(OkHttpClient.Builder().build())
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            // Fixed addresses: looking up Cloudflare's own name would go to the provider's DNS.
            .bootstrapDnsHosts(listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001").map { InetAddress.getByName(it) })
            .includeIPv6(true)
            .build()
    }

    /** Cloudflare first; the system DNS only when Cloudflare cannot be reached, so the app keeps working. */
    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = try {
            doh.lookup(hostname)
        } catch (e: UnknownHostException) {
            dnsFallbacks++
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** A bare address has no certificate to match: no HTTPS to try. */
    private fun isIp(host: String) = ':' in host || host.all { it.isDigit() || it == '.' }

    private fun mark(host: String, ok: Boolean) {
        decisions[host] = if (ok) System.currentTimeMillis() else -System.currentTimeMillis()
        val f = file ?: return
        runCatching { f.writeText(decisions.entries.joinToString("") { "${it.key} ${it.value}\n" }) }
    }

    private object Upgrade : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val url = req.url
            // Only plain HTTP on the usual port: another port has no HTTPS twin to guess.
            if (url.scheme != "http" || url.port != 80 || isIp(url.host)) return chain.proceed(req)
            val secure = req.newBuilder().url(url.newBuilder().scheme("https").port(443).build()).build()
            val d = decisions[url.host]
            if (d != null && d > 0) {
                return try { chain.proceed(secure) } catch (e: SSLException) { mark(url.host, false); chain.proceed(req) }
            }
            // Unknown (or refused a day ago): tried on an API call, never on a stream (one screen only).
            val retry = d == null || System.currentTimeMillis() + d > RETRY_REFUSED_MS
            if (retry && url.encodedPath.endsWith("/player_api.php")) {
                try {
                    val r = chain.proceed(secure)
                    val body = if (r.isSuccessful) runCatching { r.peekBody(64).string().trimStart() }.getOrDefault("") else ""
                    if (body.startsWith("{") || body.startsWith("[")) { mark(url.host, true); return r }
                    r.close(); mark(url.host, false)
                } catch (e: IOException) { mark(url.host, false) }
            }
            return chain.proceed(req)
        }
    }

    /** Sees every request actually sent, redirects included. */
    private object Watch : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (url.scheme == "http") cleartextHosts += url.host
            return chain.proceed(chain.request())
        }
    }
}
