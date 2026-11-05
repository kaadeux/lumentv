package com.lumentv.core

/** Turns any failure into a sentence that can be shown on screen, without URLs or credentials (TV, phone, PC, iPhone). */
fun userMessage(t: Throwable): String = when (t) {
    is XtreamAuthException -> t.message ?: "Identifiants Xtream refusés."
    is FormatException -> t.message ?: "Le contenu reçu n'est pas valide."
    is HttpStatusException -> when (t.code) {
        401, 403 -> "Accès refusé par le serveur (HTTP ${t.code}). Vérifiez l'adresse et les identifiants."
        404 -> "Adresse introuvable sur le serveur (HTTP 404). Vérifiez l'URL."
        429 -> "Le serveur limite les requêtes (HTTP 429). Réessayez dans quelques minutes."
        in 500..599 -> "Le serveur du fournisseur est en erreur (HTTP ${t.code}). Réessayez plus tard."
        else -> "Le serveur a répondu HTTP ${t.code}."
    }
    else -> platformMessage(t)
        ?: if (t is IOException) "Erreur réseau : ${Redact.text(t.message ?: t::class.simpleName.orEmpty())}"
        else "Erreur inattendue : ${t::class.simpleName}"
}

/** The platform's own failures (JVM: UnknownHostException, SSLException…), or null. */
internal expect fun platformMessage(t: Throwable): String?
