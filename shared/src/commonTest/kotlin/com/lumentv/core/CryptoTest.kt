package com.lumentv.core

import com.lumentv.sync.ProfileCrypto
import com.lumentv.sync.ProfileKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The profile's cryptography gives the same bytes on every device: the standard test vectors, and a profile sealed
 * by the TV / PC code (javax.crypto) that the iPhone must open. Runs on the JVM and on the iPhone simulator.
 */
class CryptoTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun sha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Crypto.sha256("abc".encodeToByteArray())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Crypto.sha256(ByteArray(0))))
    }

    @Test fun hmacRfc4231() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hex(Crypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())))
    }

    @Test fun pbkdf2Rfc7914() {
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
            "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(Crypto.pbkdf2Sha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64)))
    }

    @Test fun aesGcmStandardVectors() {
        // GCM specification (McGrew & Viega), test cases 13, 14 and 16 (AES-256).
        val zero = ByteArray(32)
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", hex(Crypto.aesGcmSeal(zero, ByteArray(12), ByteArray(0), ByteArray(0))))
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919",
            hex(Crypto.aesGcmSeal(zero, ByteArray(12), ByteArray(0), ByteArray(16))))
        val key = bytes("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv = bytes("cafebabefacedbaddecaf888")
        val plain = bytes("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
        val aad = bytes("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val sealed = Crypto.aesGcmSeal(key, iv, aad, plain)
        assertEquals("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
            "76fc6ece0f4e1768cddf8853bb2d551b", hex(sealed))
        assertContentEquals(plain, Crypto.aesGcmOpen(key, iv, aad, sealed))
        // One bit changed: refused.
        val tampered = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException> { Crypto.aesGcmOpen(key, iv, aad, tampered) }
    }

    @Test fun gzipBothWays() {
        val text = "Lumen TV ".repeat(50)
        assertEquals(text, Crypto.gunzip(Crypto.gzip(text.encodeToByteArray())).decodeToString())
        // Written by java.util.zip on the PC.
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val fromJvm = kotlin.io.encoding.Base64.decode("H4sIAAAAAAAA//MpzU3NUwgJU/AZZQxNBgB5VauAwgEAAA==")
        assertEquals(text, Crypto.gunzip(fromJvm).decodeToString())
        assertEquals("", Crypto.gunzip(Crypto.gzip(ByteArray(0))).decodeToString())
    }

    @Test fun profileSealedByTheTvOpensHere() {
        val k = ProfileKeys.derive("Famille Test", "phrase de test 123", 1000)
        assertEquals("3819d1172b8cda5044dd711fe473de98129a9c0e0009236b5926979224f688a0", k.id)
        assertEquals("8181db84ff76ca9c8a4a40107cf38d6b6bf8e4f03a1db87a2ed6cc33b7a0877b", k.token)
        val sealedOnTheTv = "/uL8kGxiH40XsPBNmPdc/Z0oRXXEQyw1njVBFJYfBte4uDPLHsEo6E1H49rpxtIq59UqY43XVEiMjUnP7aQQL19Lv38SoEQOnJ62f04sy6w="
        assertEquals("{\"profil\":\"Lumen TV\",\"é\":\"àç\"}", ProfileCrypto.open(k, sealedOnTheTv))
        // And back: sealed here, opened again; another passphrase cannot.
        val again = ProfileCrypto.seal(k, "bonjour")
        assertNotEquals(again, ProfileCrypto.seal(k, "bonjour")) // a new random nonce each time
        assertEquals("bonjour", ProfileCrypto.open(k, again))
        val other = ProfileKeys.derive("Famille Test", "autre phrase 456", 1000)
        assertFailsWith<CryptoException> { ProfileCrypto.open(other, again) }
    }
}
