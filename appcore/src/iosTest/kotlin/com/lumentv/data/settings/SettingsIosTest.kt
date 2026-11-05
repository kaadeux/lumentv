package com.lumentv.data.settings

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The settings file on the iPhone: written, read back, synced values, PIN. */
class SettingsIosTest {
    @Test
    fun writeAndRead() = runBlocking {
        val s = settingsStore(NSTemporaryDirectory() + "settings-" + NSUUID().UUIDString + ".preferences_pb")
        assertEquals(3, s.current().epgDays)
        s.set(Keys.epgDays, 5)
        s.set(Keys.subLang, "fr")
        assertEquals(5, s.current().epgDays)
        assertEquals("fr", s.syncValues()[Keys.subLang.name])
        s.setPin("1234")
        assertTrue(s.checkPin("1234"))
        assertTrue(!s.checkPin("0000"))
    }
}
