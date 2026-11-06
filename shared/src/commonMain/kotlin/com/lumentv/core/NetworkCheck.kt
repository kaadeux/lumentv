package com.lumentv.core

import kotlinx.coroutines.withContext

/** One line of the connection check: what, how it went, [level] 0 ok / 1 warning / 2 bad. */
class NetCheck(val what: String, val result: String, val level: Int)

class NetReport(val checks: List<NetCheck>, val diagnosis: String?)

/**
 * "État du réseau", the part every app shares: internet and the provider's server, timed, and the likely cause in
 * words. The device's own link (Wi-Fi signal, cable) comes from the platform as [link], with its [linkDiagnosis].
 */
object NetworkCheck {
    const val ALL_GOOD = "Tout va bien de votre côté."

    /** [provider] signs in at the provider's server (an Xtream account; null: nothing to ask it). */
    suspend fun run(http: Http, provider: (suspend () -> Unit)?, link: NetCheck? = null, linkDiagnosis: String? = null): NetReport = withContext(IoDispatcher) {
        val checks = ArrayList<NetCheck>()
        link?.let { checks += it }
        var diagnosis = linkDiagnosis
        // Internet: Cloudflare's address, no name to look up.
        val net = timed { http.execute(HttpRequest("https://1.1.1.1/cdn-cgi/trace", timeoutMs = 6_000)).use { check(it.code < 500) } }
        checks += NetCheck("Internet", net?.let { "répond en $it ms" } ?: "ne répond pas", when { net == null -> 2; net > 800 -> 1; else -> 0 })
        if (diagnosis == null && net == null) diagnosis = "Internet ne répond pas : redémarrez la box internet."
        if (diagnosis == null && net != null && net > 800) diagnosis = "Internet est lent en ce moment : un autre appareil télécharge peut-être beaucoup."
        if (provider != null) {
            val ms = timed { provider() }
            checks += NetCheck("Fournisseur", ms?.let { "répond en $it ms" } ?: "ne répond pas", when { ms == null -> 2; ms > 2500 -> 1; else -> 0 })
            if (diagnosis == null && ms == null) diagnosis = "Le serveur du fournisseur ne répond pas : ce n'est pas chez vous, réessayez plus tard."
            if (diagnosis == null && ms != null && ms > 2500) diagnosis = "Le serveur du fournisseur est lent en ce moment : les coupures viennent de lui, pas de chez vous."
        }
        NetReport(checks, diagnosis)
    }

    /** When a stream fails to play: the network's fault, the provider's server's, or the stream's own. */
    fun explainFailure(report: NetReport): String =
        report.diagnosis ?: "Ta connexion et le serveur du fournisseur répondent bien : c'est ce flux qui ne passe pas. " +
            "Essaie une autre version de la chaîne (HD, SD) ou réessaie plus tard."

    /** How long [call] takes (ms), or null when it fails. */
    private inline fun timed(call: () -> Unit): Long? {
        val t0 = nowMillis()
        return try { call(); nowMillis() - t0 } catch (e: Exception) { null }
    }
}
