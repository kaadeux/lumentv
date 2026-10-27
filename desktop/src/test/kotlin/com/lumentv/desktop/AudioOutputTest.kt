package com.lumentv.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.caprica.vlcj.log.LogLevel
import java.io.File
import java.io.RandomAccessFile

/** Plays a silent local 44.1 kHz file (resampled to the sound card's rate) and checks the audio modules VLC loads. */
class AudioOutputTest {
    @Test fun usesConfiguredOutputAndResampler() {
        val wav = File.createTempFile("silence", ".wav").apply { deleteOnExit() }
        val rate = 44100; val seconds = 3; val data = rate * 2 * 2 * seconds
        RandomAccessFile(wav, "rw").use { f ->
            fun i32(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            f.writeBytes("RIFF"); i32(36 + data); f.writeBytes("WAVEfmt "); i32(16); i16(1); i16(2); i32(rate); i32(rate * 4); i16(4); i16(16)
            f.writeBytes("data"); i32(data); f.write(ByteArray(data))
        }
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val log = Vlc.factory.application().newLog()
        log.level = LogLevel.DEBUG
        log.addLogListener { _, module, _, _, _, _, _, msg ->lines += "$module: $msg" }
        val player = Vlc.factory.mediaPlayers().newMediaPlayer()
        player.media().play(wav.absolutePath)
        Thread.sleep(2500)
        player.controls().stop(); player.release(); log.release()
        val interesting = lines.filter { it.contains("aout", true) || it.contains("resampl", true) || it.contains("directsound", true) || it.contains("mmdevice", true) || it.contains("samplerate", true) }
        File("build/audio-log.txt").writeText(lines.joinToString("\n"))
        interesting.take(40).forEach { println(it) }
        assertTrue(lines.any { it.contains("directsound", true) })
    }
}
