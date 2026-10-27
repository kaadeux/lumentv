package com.lumentv.desktop

import com.lumentv.sync.ProfileDoc
import com.lumentv.sync.ProfileMerge
import com.lumentv.sync.SyncPlaylist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.prefs.Preferences

/** Dev only (SEED_BACKUP=<backup json>): puts that playlist on this PC and unlocks the access code, for screenshots. */
class SeedTest {
    @Test fun seed() {
        val path = System.getenv("SEED_BACKUP"); assumeTrue(path != null)
        val b = Json.parseToJsonElement(File(path!!).readText()).jsonObject["playlists"]!!.jsonArray.first().jsonObject
        fun s(k: String) = b[k]!!.jsonPrimitive.content
        val p = SyncPlaylist(s("name"), s("type"), s("source"), s("username"), s("password"), at = 1)
        Store.saveDoc(ProfileDoc(playlists = mapOf(ProfileMerge.playlistKey(p.type, p.source, p.username) to p)))
        Preferences.userRoot().node("lumentv").put("unlocked", BuildInfo.ACCESS_HASH)
    }
}
