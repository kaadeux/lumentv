package com.lumentv.core

/**
 * What the internet provider can still see, as every app's settings show it (TV and phone, PC, iPhone): built from
 * the hosts moved to HTTPS and those still reached in clear (PrivateNet on the JVM, HttpsUpgrade on the iPhone).
 */
data class Privacy(val title: String, val lines: List<String>) {
    /** Some server is still reached in clear. */
    val partial get() = title == PARTIAL

    companion object {
        const val PARTIAL = "Partielle"

        /** [videoUnchecked]: the player follows redirects by itself (iPhone), so a video server in clear would not be listed. */
        fun of(secure: List<String>, clear: List<String>, dnsFallbacks: Int = 0, videoUnchecked: Boolean = false) = Privacy(
            when {
                clear.isNotEmpty() || videoUnchecked -> PARTIAL
                secure.isNotEmpty() -> "Protégée"
                else -> "DNS chiffré"
            },
            listOfNotNull(
                if (dnsFallbacks > 0) "DNS chiffré injoignable ($dnsFallbacks fois) : DNS de la box utilisé" else "Noms des sites chiffrés (Cloudflare)",
                secure.takeIf { it.isNotEmpty() }?.let { "HTTPS : ${it.joinToString(", ")}" },
                clear.takeIf { it.isNotEmpty() }?.let { "En clair, sans HTTPS chez le fournisseur (un VPN seul le cacherait) : ${it.joinToString(", ")}" },
                if (secure.isEmpty() && clear.isEmpty()) "HTTPS vérifié à la prochaine connexion au fournisseur" else null,
                if (videoUnchecked) "Vidéo non contrôlée ici : le fournisseur peut l'envoyer depuis un serveur sans HTTPS (un VPN seul le cacherait)" else null,
            ),
        )

        const val NOTE = "Votre fournisseur d'accès voit que vous utilisez internet et combien, pas ce que vous regardez quand la connexion est protégée."
    }
}

/**
 * The online profile brings the user's lists, logins included: a device joins one only once it has a list of its own,
 * so that a profile's name and passphrase alone never hand its streams to a new device.
 */
object ProfileGate {
    const val NEEDS_LIST = "Ajoute d'abord ta liste (M3U ou Xtream) : le profil en ligne ne s'ouvre qu'ensuite, pour que personne ne récupère les listes d'un profil sur un appareil vide."
}

/** The stored form of a secret (SecretBox): "p:" means the device's key store refused and it is kept in clear. */
object Secrets {
    fun inClear(vararg stored: String) = stored.any { it.startsWith("p:") }
}
