package com.lumentv.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

/** The GCM code the iPhone uses ([AesGcm], over a plain AES block) gives exactly javax.crypto's output. */
class AesGcmJvmTest {
    @Test fun sameAsJavax() {
        val rnd = Random(42)
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 100, 1000, 4097)) {
            val key = rnd.nextBytes(32); val nonce = rnd.nextBytes(12); val aad = rnd.nextBytes(size % 40); val plain = rnd.nextBytes(size)
            val gcm = AesGcm { Crypto.aesBlock(key, it) }
            val reference = Crypto.aesGcmSeal(key, nonce, aad, plain)
            assertArrayEquals("size $size", reference, gcm.seal(nonce, aad, plain))
            assertArrayEquals("size $size", plain, gcm.open(nonce, aad, reference))
        }
    }
}
