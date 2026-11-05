package com.lumentv.core

/**
 * Masks credentials before anything reaches a log, an error message or the diagnostics screen.
 * IPTV URLs carry them in the user info, in query parameters and in Xtream path segments.
 */
object Redact {
    private val userInfo = Regex("(?i)(\\w+://)[^/@\\s]+@")
    private val secretParams = Regex("(?i)([?&](?:username|user|password|pass|pwd|token|key|auth|mac)=)[^&\\s]*")
    private val xtreamPath = Regex("(?i)(/(?:live|movie|series|timeshift)/)[^/\\s]+/[^/\\s]+/")

    fun url(url: String): String = text(url)

    fun text(text: String): String = text
        .replace(userInfo, "$1***@")
        .replace(secretParams, "$1***")
        .replace(xtreamPath, "$1***/***/")
}
