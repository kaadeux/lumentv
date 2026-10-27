package com.lumentv.core

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A server answered with an HTTP error status. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** The content downloaded is not a playlist / guide we can read. */
class FormatException(message: String) : IOException(message)

/** Xtream Codes refused the login (auth = 0, expired or disabled account). */
class XtreamAuthException(message: String) : IOException(message)

/** Turns any failure into a sentence that can be shown on the TV, without URLs or credentials. */
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
    is UnknownHostException -> "Serveur introuvable. Vérifiez l'adresse et la connexion Internet."
    is SocketTimeoutException -> "Le serveur ne répond pas (délai dépassé)."
    is ConnectException -> "Connexion au serveur impossible."
    is SSLException -> "Erreur de connexion sécurisée (certificat ou HTTPS)."
    is FileNotFoundException -> "Fichier introuvable ou illisible."
    is SecurityException -> "Accès au fichier refusé."
    is OutOfMemoryError -> "Liste trop volumineuse pour la mémoire de l'appareil."
    is IOException -> "Erreur réseau : ${Redact.text(t.message ?: t.javaClass.simpleName)}"
    else -> "Erreur inattendue : ${t.javaClass.simpleName}"
}
