package com.lumentv.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Dev only (CLEAN_HISTORY=list|delete): finds the history entries written by the player tests in the last
 * CLEAN_HOURS hours (From S1 E1, Monte-Cristo HD, TF1 HD, FRANCE 2 HD), lists them, and with "delete" removes them
 * and syncs the profile. Writes a report to build/clean.txt.
 */
class CleanHistoryTest {
    @Test fun clean() = runBlocking {
        val mode = System.getenv("CLEAN_HISTORY"); assumeTrue(mode != null)
        val state = AppState(CoroutineScope(Dispatchers.Default))
        state.syncNow()
        val since = System.currentTimeMillis() - (System.getenv("CLEAN_HOURS")?.toLong() ?: 3) * 3_600_000L
        val fmt = SimpleDateFormat("dd/MM HH:mm")
        val targets = state.doc.history.values.filter { h ->
            !h.deleted && h.watchedAt > since && (
                (h.kind == Kind.EPISODE && h.title.equals("From", true) && h.subtitle?.startsWith("S1 E1 ") == true) ||
                (h.kind == Kind.MOVIE && h.title.contains("Monte-Cristo", true) && h.title.contains(" HD ", true)) ||
                (h.kind == Kind.CHANNEL && (h.title == "TF1 HD" || h.title == "FRANCE 2 HD")))
        }
        val out = StringBuilder()
        targets.forEach { out.appendLine("${it.kind} | ${it.title} | ${it.subtitle ?: ""} | ${fmt.format(Date(it.watchedAt))} | ${it.positionMs / 1000}s") }
        if (mode == "delete" && targets.isNotEmpty()) {
            state.markWatched(targets.map { PlayItem(it.kind, it.uid, it.title, it.subtitle, it.image, it.parentUid, "", it.kind == Kind.CHANNEL) },
                targets.map { 0L }, watched = false)
            val r = state.syncNow()
            out.appendLine("deleted ${targets.size}, sync ${if (r.isSuccess) "ok" else "failed: ${r.exceptionOrNull()?.message}"}")
            out.appendLine("left in history: " + state.history().take(6).joinToString(" ; ") { "${it.title} ${it.subtitle ?: ""}" })
        }
        File("build/clean.txt").writeText(out.toString())
    }
}
