package com.lumentv.desktop

import com.lumentv.sync.ProfileKeys
import com.lumentv.sync.ProfileSync
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end check on a real profile (no window): profile → playlist → guide → VLC playback.
 * Env: SYNC_TEST_URL, SYNC_PROBE_NAME, SYNC_PROBE_PASS. Writes a summary without secrets to build/smoke.txt.
 */
class SmokeTest {
    @Test fun pipeline() = runBlocking {
        val url = System.getenv("SYNC_TEST_URL"); val name = System.getenv("SYNC_PROBE_NAME"); val pass = System.getenv("SYNC_PROBE_PASS")
        assumeTrue(url != null && name != null && pass != null)
        val out = StringBuilder()
        val http = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
        val doc = ProfileSync(http, url!!).pull(ProfileKeys.derive(name!!, pass!!))!!.doc
        val p = doc.playlists.values.first { !it.deleted }
        val lib = Library(http)
        var t = System.currentTimeMillis()
        val c = lib.load(p)
        out.appendLine("catalog: ${c.channels.size} channels, ${c.movies.size} movies, ${c.series.size} series in ${(System.currentTimeMillis() - t) / 1000} s")
        t = System.currentTimeMillis()
        val g = lib.guide(c, p.epgOffsetMinutes, p.userAgent)
        out.appendLine("guide: ${g.size} channels with programmes in ${(System.currentTimeMillis() - t) / 1000} s")
        val tf1 = c.channels.firstOrNull { it.name.startsWith("TF1") } ?: c.channels.first()
        out.appendLine("now on ${tf1.name}: " + (g[tf1.uid]?.firstOrNull { System.currentTimeMillis() in it.start until it.stop }?.title ?: "?"))
        val s = c.series.first { it.name.contains("MINDHUNTER", ignoreCase = true) }
        out.appendLine("episodes of ${s.name}: ${lib.episodes(p, c, s).size}")

        val mp = Vlc.factory.mediaPlayers().newMediaPlayer()
        val playing = CountDownLatch(1)
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = playing.countDown()
        })
        mp.audio().setVolume(0)
        mp.media().play(lib.channelUrl(p, tf1))
        val ok = playing.await(30, TimeUnit.SECONDS)
        Thread.sleep(4000)
        out.appendLine("vlc playing: $ok, time=${mp.status().time()} ms, video=${mp.video().videoDimension()}")
        mp.controls().stop(); mp.release()
        File("build/smoke.txt").writeText(out.toString())
        assertTrue(c.channels.isNotEmpty() && ok)
    }
}
