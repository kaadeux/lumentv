@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.lumentv.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

/** iPhone: Apple's CommonCrypto and zlib; GCM written out in shared Kotlin ([AesGcm]) over CommonCrypto's AES. */
actual object Crypto {
    actual fun sha256(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        data.pinned { d -> out.usePinned { o -> CC_SHA256(d, data.size.convert(), o.addressOf(0).reinterpret<UByteVar>()) } }
        return out
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(32)
        key.pinned { k -> data.pinned { d -> out.usePinned { o -> CCHmac(kCCHmacAlgSHA256, k, key.size.convert(), d, data.size.convert(), o.addressOf(0)) } } }
        return out
    }

    actual fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        val status = password.pinned { p ->
            salt.pinned { s ->
                out.usePinned { o ->
                    CCKeyDerivationPBKDF(kCCPBKDF2, p, password.size.convert(), s.reinterpret<UByteVar>(), salt.size.convert(),
                        kCCPRFHmacAlgSHA256, iterations.convert(), o.addressOf(0).reinterpret<UByteVar>(), length.convert())
                }
            }
        }
        if (status != kCCSuccess) throw CryptoException("PBKDF2 : erreur $status")
        return out
    }

    actual fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        if (size == 0) return out
        val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
        if (status != errSecSuccess) throw CryptoException("aléa indisponible ($status)")
        return out
    }

    actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        AesGcm { aesBlock(key, it) }.seal(nonce, aad, plain)

    actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray =
        AesGcm { aesBlock(key, it) }.open(nonce, aad, sealed)

    actual fun gzip(data: ByteArray): ByteArray = zlib(data, compress = true)

    actual fun gunzip(data: ByteArray): ByteArray = zlib(data, compress = false)

    /** One AES block (ECB, no padding), the building block of [AesGcm]. */
    internal fun aesBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(block.size == 16)
        val out = ByteArray(16)
        val status = memScoped {
            val moved = alloc<size_tVar>()
            key.pinned { k ->
                block.pinned { b ->
                    out.usePinned { o ->
                        CCCrypt(kCCEncrypt, kCCAlgorithmAES, kCCOptionECBMode, k, key.size.convert(), null, b, 16.convert(), o.addressOf(0), 16.convert(), moved.ptr)
                    }
                }
            }
        }
        if (status != kCCSuccess) throw CryptoException("AES : erreur $status")
        return out
    }

    /** gzip format (windowBits 15 + 16), as java.util.zip.GZIPOutputStream writes and GZIPInputStream reads. */
    private fun zlib(data: ByteArray, compress: Boolean): ByteArray = memScoped {
        val strm = alloc<z_stream>()
        strm.zalloc = null
        strm.zfree = null
        strm.opaque = null
        strm.next_in = null
        strm.avail_in = 0u
        val init = if (compress) deflateInit2_(strm.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 31, 8, Z_DEFAULT_STRATEGY, zlibVersion(), sizeOf<z_stream>().toInt())
        else inflateInit2_(strm.ptr, 31, zlibVersion(), sizeOf<z_stream>().toInt())
        if (init != Z_OK) throw CryptoException("zlib : initialisation ($init)")
        val chunks = ArrayList<ByteArray>()
        val buffer = ByteArray(64 * 1024)
        val input = if (data.isEmpty()) ByteArray(1) else data
        try {
            input.usePinned { inp ->
                strm.next_in = inp.addressOf(0).reinterpret()
                strm.avail_in = data.size.convert()
                buffer.usePinned { buf ->
                    while (true) {
                        strm.next_out = buf.addressOf(0).reinterpret()
                        strm.avail_out = buffer.size.convert()
                        val r = if (compress) deflate(strm.ptr, Z_FINISH) else inflate(strm.ptr, Z_NO_FLUSH)
                        val produced = buffer.size - strm.avail_out.toInt()
                        if (produced > 0) chunks += buffer.copyOf(produced)
                        if (r == Z_STREAM_END) break
                        if (r != Z_OK) throw CryptoException("zlib : données invalides ($r)")
                        if (!compress && strm.avail_in == 0u && produced == 0) throw CryptoException("zlib : données tronquées")
                    }
                }
            }
        } finally {
            if (compress) deflateEnd(strm.ptr) else inflateEnd(strm.ptr)
        }
        val out = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { c.copyInto(out, pos); pos += c.size }
        out
    }

    /** The bytes as a C pointer (a 1-byte placeholder for an empty array: C wants a valid address). */
    private inline fun <R> ByteArray.pinned(block: (CPointer<ByteVar>) -> R): R {
        val bytes = if (isEmpty()) ByteArray(1) else this
        return bytes.usePinned { block(it.addressOf(0)) }
    }
}
