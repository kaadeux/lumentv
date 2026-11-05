@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.lumentv.core

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pin
import kotlinx.cinterop.free
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

actual class TextFile actual constructor(private val path: String) {
    actual fun read(): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    @Suppress("CAST_NEVER_SUCCEEDS")
    actual fun write(text: String): Boolean {
        val dir = (path as NSString).stringByDeletingLastPathComponent
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}

/** zlib's inflate fed piece by piece from [body] (gzip format: windowBits 15 + 16). */
actual fun gunzip(body: HttpBody): HttpBody = object : HttpBody {
    private val strm = nativeHeap.alloc<z_stream>().also { s ->
        s.zalloc = null; s.zfree = null; s.opaque = null; s.next_in = null; s.avail_in = 0u
        if (inflateInit2_(s.ptr, 31, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) != Z_OK) throw IOException("zlib : initialisation")
    }
    private val input = ByteArray(64 * 1024)
    private val inputPinned = input.pin()
    private var inputEnded = false
    private var finished = false
    private var closed = false

    override fun read(buffer: ByteArray): Int {
        if (finished || buffer.isEmpty()) return -1
        while (true) {
            if (strm.avail_in == 0u && !inputEnded) {
                val n = body.read(input)
                if (n < 0) inputEnded = true
                else { strm.next_in = inputPinned.addressOf(0).reinterpret(); strm.avail_in = n.convert() }
            }
            val produced = buffer.usePinned { out ->
                strm.next_out = out.addressOf(0).reinterpret()
                strm.avail_out = buffer.size.convert()
                val r = inflate(strm.ptr, Z_NO_FLUSH)
                if (r == Z_STREAM_END) finished = true
                else if (r != Z_OK && !(r == platform.zlib.Z_BUF_ERROR && !inputEnded)) throw IOException("Données compressées invalides ($r).")
                buffer.size - strm.avail_out.toInt()
            }
            if (produced > 0) return produced
            if (finished || (inputEnded && strm.avail_in == 0u)) { finished = true; return -1 }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        inflateEnd(strm.ptr)
        nativeHeap.free(strm)
        inputPinned.unpin()
        body.close()
    }
}
