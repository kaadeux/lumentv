package com.lumentv.core

/** TV, phone and PC: the JVM's own (OkHttp, files and the existing code throw and catch these). */
actual typealias IOException = java.io.IOException

internal actual fun decomposeUnicode(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
