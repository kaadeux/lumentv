package com.lumentv.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Dev only (DL_SMOKE=1): downloads the first episode of DL_SERIES for 12 s, checks progress, then deletes it. */
class DownloadSmokeTest {
    @Test fun download() = runBlocking {
        assumeTrue(System.getenv("DL_SMOKE") == "1")
        val state = AppState(CoroutineScope(Dispatchers.Swing))
        val key = state.playlists.keys.first()
        val c = Store.loadCatalog(key)!!
        state.open(key); Thread.sleep(3000)
        val s = c.series.first { it.name.equals(System.getenv("DL_SERIES") ?: "From", true) }
        val eps = state.library.episodes(state.active!!, c, s)
        kotlinx.coroutines.withContext(Dispatchers.Swing) { state.download(state.episodeItem(s, eps, 0, fromStart = true)) }
        val out = StringBuilder()
        repeat(6) { Thread.sleep(2000); val t = state.downloads.tasks.first(); out.appendLine("${t.status} ${Downloads.size(t.done)} / ${Downloads.size(t.total)} ${Downloads.size(t.speed)}/s") }
        val t = state.downloads.tasks.first()
        out.appendLine("file exists=${File(t.file).exists()} size=${Downloads.size(File(t.file).length())} name=${File(t.file).name}")
        kotlinx.coroutines.withContext(Dispatchers.Swing) { state.downloads.remove(t.id) }
        Thread.sleep(1000)
        out.appendLine("after remove: tasks=${state.downloads.tasks.size} file exists=${File(t.file).exists()}")
        File("build/dl.txt").writeText(out.toString())
    }
}
