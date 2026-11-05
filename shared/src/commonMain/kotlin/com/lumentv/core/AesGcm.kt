package com.lumentv.core

/**
 * GCM mode (NIST SP 800-38D) over any AES block function, for platforms whose system crypto gives AES but not GCM
 * to Kotlin (the iPhone: CommonCrypto has AES, GCM only in CryptoKit, which is Swift-only). 96-bit nonces and
 * 128-bit tags, the only ones the profile uses. The data is small (a gzipped profile): simplicity over speed.
 */
internal class AesGcm(private val encryptBlock: (ByteArray) -> ByteArray) {
    private val h = encryptBlock(ByteArray(16))

    fun seal(nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(nonce.size == 12) { "nonce de 12 octets attendu" }
        val j0 = nonce + byteArrayOf(0, 0, 0, 1)
        val cipher = ctr(inc32(j0), plain)
        return cipher + tag(j0, aad, cipher)
    }

    fun open(nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(nonce.size == 12) { "nonce de 12 octets attendu" }
        if (sealed.size < 16) throw CryptoException("données trop courtes")
        val cipher = sealed.copyOfRange(0, sealed.size - 16)
        val j0 = nonce + byteArrayOf(0, 0, 0, 1)
        val expected = tag(j0, aad, cipher)
        // Constant-time comparison of the tags.
        var diff = 0
        for (i in 0 until 16) diff = diff or (expected[i].toInt() xor sealed[sealed.size - 16 + i].toInt())
        if (diff != 0) throw CryptoException("étiquette GCM invalide")
        return ctr(inc32(j0), cipher)
    }

    private fun ctr(start: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var counter = start
        var pos = 0
        while (pos < data.size) {
            val ks = encryptBlock(counter)
            val n = minOf(16, data.size - pos)
            for (i in 0 until n) out[pos + i] = (data[pos + i].toInt() xor ks[i].toInt()).toByte()
            pos += n
            counter = inc32(counter)
        }
        return out
    }

    private fun tag(j0: ByteArray, aad: ByteArray, cipher: ByteArray): ByteArray {
        var y = ByteArray(16)
        fun absorb(data: ByteArray) {
            var pos = 0
            while (pos < data.size) {
                val block = ByteArray(16)
                data.copyInto(block, 0, pos, minOf(pos + 16, data.size))
                for (i in 0 until 16) block[i] = (block[i].toInt() xor y[i].toInt()).toByte()
                y = gfMul(block, h)
                pos += 16
            }
        }
        absorb(aad)
        absorb(cipher)
        val lengths = ByteArray(16)
        putLong(lengths, 0, aad.size.toLong() * 8)
        putLong(lengths, 8, cipher.size.toLong() * 8)
        for (i in 0 until 16) lengths[i] = (lengths[i].toInt() xor y[i].toInt()).toByte()
        y = gfMul(lengths, h)
        val ek = encryptBlock(j0)
        return ByteArray(16) { (y[it].toInt() xor ek[it].toInt()).toByte() }
    }

    private companion object {
        /** The last 32 bits plus one (the counter of GCM's CTR mode). */
        fun inc32(block: ByteArray): ByteArray {
            val out = block.copyOf()
            var i = 15
            while (i >= 12) {
                val v = (out[i].toInt() and 0xff) + 1
                out[i] = v.toByte()
                if (v <= 0xff) break
                i--
            }
            return out
        }

        fun putLong(b: ByteArray, at: Int, v: Long) { for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte() }

        /** Multiplication in GF(2^128) with GCM's bit order (SP 800-38D, algorithm 1). */
        fun gfMul(x: ByteArray, y: ByteArray): ByteArray {
            val z = ByteArray(16)
            val v = y.copyOf()
            for (i in 0 until 128) {
                if ((x[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) for (k in 0 until 16) z[k] = (z[k].toInt() xor v[k].toInt()).toByte()
                val lsb = v[15].toInt() and 1
                for (k in 15 downTo 1) v[k] = (((v[k].toInt() and 0xff) ushr 1) or ((v[k - 1].toInt() and 1) shl 7)).toByte()
                v[0] = ((v[0].toInt() and 0xff) ushr 1).toByte()
                if (lsb == 1) v[0] = (v[0].toInt() xor 0xe1).toByte()
            }
            return z
        }
    }
}
