package com.lumentv.desktop

import com.lumentv.sync.ProfileKeys
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64
import java.util.prefs.Preferences

/** Dev only: pre-connects this PC to a test profile (SEED_PROFILE=1 + SYNC_PROBE_NAME / SYNC_PROBE_PASS). */
class SeedTest {
    @Test fun seed() {
        assumeTrue(System.getenv("SEED_PROFILE") == "1")
        val k = ProfileKeys.derive(System.getenv("SYNC_PROBE_NAME"), System.getenv("SYNC_PROBE_PASS"))
        Store.saveKeys(Store.StoredKeys(k.name, k.id, k.token, Base64.getEncoder().encodeToString(k.encKey)))
        Preferences.userRoot().node("lumentv").put("unlocked", BuildInfo.ACCESS_HASH)
    }
}
