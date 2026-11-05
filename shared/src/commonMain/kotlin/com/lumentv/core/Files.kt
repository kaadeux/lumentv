package com.lumentv.core

/** A small text file of the app (a cache): read whole, written whole. JVM: java.io.File; iPhone: Foundation. */
expect class TextFile(path: String) {
    /** The content, or null when the file does not exist or cannot be read. */
    fun read(): String?
    /** Writes [text], creating the folder if needed; false when it cannot. */
    fun write(text: String): Boolean
}

/** The body decompressed (gzip) as it is read: a 9 MB guide never sits whole in memory. */
expect fun gunzip(body: HttpBody): HttpBody
