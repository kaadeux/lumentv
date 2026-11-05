package com.lumentv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SecretBoxIosTest {
    @Test
    fun roundTrip() {
        val stored = SecretBox.encrypt("http://serveur:8080/mot de passe é")
        assertNotEquals("", stored)
        assertEquals("http://serveur:8080/mot de passe é", SecretBox.decrypt(stored))
        // In the simulator's test runner the Keychain may be refused: then "p:" as on Android.
        println("SecretBox format on this device: " + stored.substringBefore(':'))
        assertEquals("", SecretBox.encrypt(""))
        assertEquals("x", SecretBox.decrypt("p:x"))
    }
}
