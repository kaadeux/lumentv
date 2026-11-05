package com.lumentv.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The iPhone's part of what the TV and the PC do in PrivateNet (jvmMain), to keep what is watched away from the
 * internet provider: a server reached over plain HTTP is moved to HTTPS when it answers there (tried once on its
 * player_api.php, the answer kept in a file); the addresses, which hold the login and what is played, are then
 * encrypted. Names are looked up over HTTPS at Cloudflare by the Swift app (iOS privacy context).
 */
object HttpsUpgrade {
    private const val RETRY_REFUSED_MS = 24 * 3_600_000L
    private val lock = SynchronizedObject()
    private var file: TextFile? = null
    /** host → time of the decision: positive = HTTPS works, negative = refused. */
    private val decisions = HashMap<String, Long>()
    private val clear = HashSet<String>()

    /** Loads the decisions kept from earlier runs (host names only). Call once at start-up. */
    fun init(store: TextFile) = synchronized(lock) {
        file = store
        store.read()?.lineSequence()?.forEach { l ->
            val parts = l.split(' ')
            if (parts.size == 2) parts[1].toLongOrNull()?.let { decisions[parts[0]] = it }
        }
    }

    /** Hosts moved to HTTPS, and those still reached in clear since the app started. */
    val secureHosts: List<String> get() = synchronized(lock) { decisions.filterValues { it > 0 }.keys.sorted() }
    val cleartextHosts: List<String> get() = synchronized(lock) { clear.sorted() }

    private class Parts(val host: String, val port: Int, val path: String)

    /** Plain HTTP on the usual port to a named host: the only case with an HTTPS twin to guess. */
    private fun plain(url: String): Parts? {
        if (!url.startsWith("http://", true)) return null
        val rest = url.substring(7)
        val authority = rest.substringBefore('/').substringBefore('?').substringAfterLast('@')
        val host = if (authority.startsWith("[")) return null else authority.substringBefore(':').lowercase()
        val port = authority.substringAfter(':', "80").toIntOrNull() ?: return null
        if (host.isEmpty() || host.all { it.isDigit() || it == '.' }) return null
        return Parts(host, port, "/" + rest.substringAfter('/', "").substringBefore('?'))
    }

    private fun secure(url: String) = "https://" + url.substring(7).replaceFirst(Regex("^([^/?]*?):80(?=[/?]|$)"), "$1")

    private fun mark(host: String, ok: Boolean) = synchronized(lock) {
        decisions[host] = if (ok) nowMillis() else -nowMillis()
        file?.write(decisions.entries.joinToString("") { "${it.key} ${it.value}\n" })
    }

    private fun sawClear(url: String) {
        if (!url.startsWith("http://", true)) return
        val host = url.substring(7).substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':')
        synchronized(lock) { clear += host }
    }

    /** The address to give the player: the HTTPS one when its server is known to answer there. */
    fun stream(url: String): String {
        val p = plain(url)
        val d = p?.takeIf { it.port == 80 }?.let { synchronized(lock) { decisions[it.host] } }
        if (d != null && d > 0) return secure(url)
        sawClear(url)
        return url
    }

    /** [inner] with the HTTPS upgrade. */
    fun wrap(inner: Http): Http = object : Http {
        override fun execute(request: HttpRequest): HttpResponse {
            val p = plain(request.url)?.takeIf { it.port == 80 } ?: return inner.execute(request).also { sawClear(request.url) }
            val https = HttpRequest(secure(request.url), request.method, request.headers, request.body, request.contentType, request.timeoutMs)
            val d = synchronized(lock) { decisions[p.host] }
            if (d != null && d > 0) {
                // A server whose API works over HTTPS may still refuse another address there: plain HTTP then.
                val r = runCatching { inner.execute(https) }.getOrNull()
                if (r != null && r.code < 400) return r
                r?.close()
                return inner.execute(request).also { sawClear(request.url) }
            }
            // Unknown (or refused a day ago): tried on an API call only.
            val retry = d == null || nowMillis() + d > RETRY_REFUSED_MS
            if (retry && p.path.endsWith("/player_api.php")) {
                try {
                    val r = inner.execute(https)
                    val text = r.use { if (it.isSuccessful) it.text() else "" }
                    val t = text.trimStart()
                    if (t.startsWith("{") || t.startsWith("[")) {
                        mark(p.host, true)
                        return HttpResponse(r.code, { r.header(it) }, HttpBody.of(text.encodeToByteArray()))
                    }
                    mark(p.host, false)
                } catch (e: Exception) {
                    mark(p.host, false)
                }
            }
            return inner.execute(request).also { sawClear(request.url) }
        }
    }

    /** For the settings: what the internet provider can still see. */
    fun summary(): Privacy = Privacy.of(secureHosts, cleartextHosts)
}
