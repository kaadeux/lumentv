package com.lumentv.core

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal actual fun platformMessage(t: Throwable): String? = when (t) {
    is UnknownHostException -> "Serveur introuvable. Vérifiez l'adresse et la connexion Internet."
    is SocketTimeoutException -> "Le serveur ne répond pas (délai dépassé)."
    is ConnectException -> "Connexion au serveur impossible."
    is SSLException -> "Erreur de connexion sécurisée (certificat ou HTTPS)."
    is FileNotFoundException -> "Fichier introuvable ou illisible."
    is SecurityException -> "Accès au fichier refusé."
    is OutOfMemoryError -> "Liste trop volumineuse pour la mémoire de l'appareil."
    else -> null
}
