package com.lumentv.data.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FixtureTimeTest {
    private val t = 1_793_908_800_000L // 2026-11-05T20:00:00Z

    @Test
    fun isoTimes() {
        assertEquals(t, FixtureClient.isoMillis("2026-11-05T20:00Z"))
        assertEquals(t, FixtureClient.isoMillis("2026-11-05T20:00:00"))
        assertEquals(t, FixtureClient.isoMillis("2026-11-05T21:00:00+01:00"))
        assertEquals(t, FixtureClient.isoMillis("2026-11-05T15:00:00-0500"))
        assertEquals(t + 30_000, FixtureClient.isoMillis("2026-11-05T20:00:30.000Z"))
        assertNull(FixtureClient.isoMillis("2026-13-05T20:00Z"))
        assertNull(FixtureClient.isoMillis("demain"))
    }
}
