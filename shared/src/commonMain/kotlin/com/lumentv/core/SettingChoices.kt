package com.lumentv.core

/** What the settings offer, the same on every app (TV and phone, iPhone, PC). */
object SettingChoices {
    /** "" = automatic: the device's language. */
    val languages = listOf(
        "" to "Automatique", "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "de" to "Allemand",
        "it" to "Italien", "pt" to "Portugais", "ar" to "Arabe", "nl" to "Néerlandais",
    )
    fun languageLabel(code: String) = languages.firstOrNull { it.first == code }?.second ?: code

    /** The words naming a language in the name of an audio or subtitle track. */
    val languageWords = mapOf(
        "fr" to listOf("fr", "fre", "fra", "français", "francais", "french", "vf", "vff", "vfq"),
        "en" to listOf("en", "eng", "english", "anglais", "vo"),
        "es" to listOf("es", "spa", "esp", "español", "espanol", "spanish", "espagnol"),
        "de" to listOf("de", "ger", "deu", "deutsch", "german", "allemand"),
        "it" to listOf("it", "ita", "italiano", "italian", "italien"),
        "pt" to listOf("pt", "por", "português", "portugues", "portuguese", "portugais"),
        "ar" to listOf("ar", "ara", "arabic", "arabe"),
        "nl" to listOf("nl", "dut", "nld", "nederlands", "dutch", "néerlandais"),
    )

    /** Days of programmes kept. */
    val epgDays = listOf(1, 2, 3, 5, 7)

    /** Hours between two automatic reloads of a list (0: never). */
    val reloadHours = listOf(0 to "Jamais", 6 to "6 h", 12 to "12 h", 24 to "24 h", 48 to "48 h", 168 to "7 j")

    /** The picture: 0 = fit, 1 = zoom (crop), 2 = stretch (Settings.resizeMode). */
    val resizeModes = listOf(0 to "Adapter", 1 to "Remplir", 2 to "Étirer")

    /** What opens with the app (Settings.startup). */
    val startups = listOf("home" to "L'accueil", "last" to "La dernière chaîne")

    /** Some providers only accept some players. */
    val userAgents = listOf(
        "" to "Automatique", "VLC/3.0.20 LibVLC/3.0.20" to "VLC", "IPTVSmarters/1.0" to "IPTV Smarters",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" to "Navigateur Chrome",
    )
    fun userAgentLabel(value: String) = userAgents.firstOrNull { it.first == value }?.second ?: "Personnalisé"
}
