package com.lumentv.desktop

import com.lumentv.core.asHttp
import com.lumentv.data.xtream.Presence
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** A download removed while it runs leaves nothing on the disk (Windows refuses to delete a file still open). */
class DownloadRemoveTest {
    @Test fun removedWhileRunning() = runBlocking(Dispatchers.Main) {
        val dir = Files.createTempDirectory("lumen-dl").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/film.mkv") { x ->
            runCatching {
                x.sendResponseHeaders(200, 0)
                x.responseBody.use { out -> repeat(600) { out.write(ByteArray(64 * 1024)); out.flush(); Thread.sleep(50) } }
            }
        }
        server.start()
        try {
            val http = OkHttpClient()
            val downloads = Downloads(CoroutineScope(SupervisorJob() + Dispatchers.Main), http, Presence(http.asHttp(), "", "test", "test"),
                File(dir, "downloads.json"), File(dir, "films")) { null }
            downloads.add(Kind.MOVIE, "u", "p", "Film", null, null, null, "http://127.0.0.1:${server.address.port}/film.mkv", "")
            val task = downloads.tasks.single()
            val file = File(task.file)
            withContext(Dispatchers.IO) { repeat(100) { if (file.length() > 0) return@withContext; Thread.sleep(100) } }
            assertTrue("le téléchargement a commencé", file.length() > 0)

            downloads.remove(task.id)

            withContext(Dispatchers.IO) { repeat(50) { if (!file.exists()) return@withContext; Thread.sleep(100) } }
            assertFalse("le fichier partiel doit disparaître", file.exists())
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
