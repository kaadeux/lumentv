package com.lumentv.desktop

import com.lumentv.core.asHttp
import com.lumentv.data.xtream.XtreamApi
import com.lumentv.data.xtream.XtreamCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Dev only (HOLD_BACKUP=<backup json with credentials>): plays the first channel for HOLD_SECONDS, to take a screen
 * of the subscription while another device is tested. Logs the provider's connection count, never the credentials.
 */
class HoldStreamTest {
    @Test fun hold() {
        val path = System.getenv("HOLD_BACKUP"); assumeTrue(path != null)
        val p = Json.parseToJsonElement(File(path!!).readText()).jsonObject["playlists"]!!.jsonArray.first().jsonObject
        fun s(k: String) = p[k]!!.jsonPrimitive.content
        val api = XtreamApi(OkHttpClient().asHttp(), XtreamCredentials(s("source"), s("username"), s("password")), null)
        val log = StringBuilder()
        log.appendLine("before: ${api.authenticate().summary()}")
        val mp = Vlc.factory.mediaPlayers().newMediaPlayer()
        mp.audio().setVolume(0)
        val firstLive = 1L.let { var id = 0L; api.liveStreams { if (id == 0L) id = it.streamId }; id }
        mp.media().play(api.liveUrl(firstLive, "ts"))
        val presence = com.lumentv.data.xtream.Presence(OkHttpClient(), BuildInfo.SYNC_URL, "hold-test", "PC de test")
        val key = com.lumentv.data.xtream.Presence.key("xtream", s("source"), s("username"), s("password"))
        val beats = Thread { try { while (true) { kotlinx.coroutines.runBlocking { presence.beat(key) }; Thread.sleep(30_000) } } catch (_: InterruptedException) {} }.apply { start() }
        Thread.sleep(15_000)
        log.appendLine("while playing: ${api.authenticate().summary()} (playing=${mp.status().isPlaying})")
        File("build/hold.txt").writeText(log.toString())
        Thread.sleep((System.getenv("HOLD_SECONDS")?.toLong() ?: 90) * 1000 - 15_000)
        mp.controls().stop(); mp.release()
        beats.interrupt(); kotlinx.coroutines.runBlocking { presence.leave(key) }
        log.appendLine("after stop: ${api.authenticate().summary()}")
        File("build/hold.txt").writeText(log.toString())
    }
}
