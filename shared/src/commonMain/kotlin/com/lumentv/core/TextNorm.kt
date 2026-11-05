package com.lumentv.core

/** Search key: lower case, accents removed, punctuation folded to single spaces. "Télé-Matin" -> "tele matin". */
fun normalize(text: String): String {
    // Unicode decomposition is the slow part: skip it for plain ASCII names, the usual case.
    val decomposed = if (text.all { it.code < 128 }) text else decomposeUnicode(text)
    val sb = StringBuilder(decomposed.length)
    var lastSpace = true
    for (c in decomposed) {
        when {
            c.category == CharCategory.NON_SPACING_MARK -> Unit
            c.isLetterOrDigit() -> { sb.append(c.lowercaseChar()); lastSpace = false }
            !lastSpace -> { sb.append(' '); lastSpace = true }
        }
    }
    return sb.toString().trim()
}

/** Removes the quality / region tags providers add, to compare channel names: "FR| TF1 HD" -> "tf1". */
fun channelKeyName(name: String): String {
    val n = normalize(name.replace(countryPrefix, ""))
    return n.split(' ')
        .filterNot { it in qualityTags }
        .joinToString(" ")
        .ifBlank { normalize(name) }
}

private val countryPrefix = Regex("^\\s*[A-Za-z]{2,3}\\s*[|:]\\s*")
private val qualityTags = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "h264", "1080p", "720p", "hq", "lq", "backup", "vip")
