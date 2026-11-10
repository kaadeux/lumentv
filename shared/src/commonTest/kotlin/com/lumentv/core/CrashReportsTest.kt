package com.lumentv.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportsTest {
    @Test fun noAccountNorAddressLeaves() {
        val t = IllegalStateException("http://user:secret@myserver.example:8080/live/john/pass123/42.ts failed at 192.168.1.160:5555")
        val r = CrashReports.report(t, CrashReports.App("test", "1", "box", "os")).toString()
        listOf("secret", "pass123", "john", "myserver", "192.168.1.160").forEach { assertFalse(it in r, it) }
        assertTrue("IllegalStateException" in r)
    }
}
