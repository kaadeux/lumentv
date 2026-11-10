package com.lumentv.desktop

import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import com.sun.jna.platform.win32.Crypt32Util
import com.lumentv.sync.ProfileDoc
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Files of the Windows app, in %APPDATA%\LumenTV. Anything holding a password or the profile keys is encrypted
 * with Windows DPAPI: only this Windows account on this PC can read it back.
 */
object Store {
    val dir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "LumenTV").apply { mkdirs() }
    private val catalogDir = File(dir, "catalog").apply { mkdirs() }

    /** Posters and logos (Coil's disk cache). Coil's old default folder, in the temp folder, is emptied once. */
    val imageCache: okio.Path by lazy {
        File(System.getProperty("java.io.tmpdir"), "coil3_disk_cache").takeIf { it.isDirectory }?.let { old -> runCatching { old.deleteRecursively() } }
        okio.Path.Companion.run { File(dir, "images").toOkioPath() }
    }
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    class StoredKeys(val name: String, val id: String, val token: String, val key: String, val version: Int = 0)

    /** This PC's data: playlists, favourites, history, settings, each entry dated for the merge. */
    fun loadDoc(): ProfileDoc = readSecret("state.bin")?.let { runCatching { json.decodeFromString<ProfileDoc>(it) }.getOrNull() } ?: ProfileDoc()
    fun saveDoc(d: ProfileDoc) = writeSecret("state.bin", json.encodeToString(d))

    /** How the window is drawn (OPENGL, DIRECT3D or SOFTWARE). Kept on this PC only: it depends on its graphics card. */
    var renderApi: String
        get() = File(dir, "render.txt").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it in RENDER_APIS } ?: "OPENGL"
        set(v) = File(dir, "render.txt").writeText(v)
    val RENDER_APIS = listOf("OPENGL", "DIRECT3D", "SOFTWARE")

    /** VLC audio output (directsound, mmdevice = WASAPI, waveout). On this PC only: it depends on its sound card. */
    var audioOut: String
        get() = File(dir, "audio.txt").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it in AUDIO_OUTS } ?: "directsound"
        set(v) = File(dir, "audio.txt").writeText(v)
    val AUDIO_OUTS = listOf("directsound", "mmdevice", "waveout")

    fun loadKeys(): StoredKeys? = readSecret("profile.bin")?.let { runCatching { json.decodeFromString<StoredKeys>(it) }.getOrNull() }
    fun saveKeys(k: StoredKeys?) { if (k == null) File(dir, "profile.bin").delete() else writeSecret("profile.bin", json.encodeToString(k)) }

    // Read and written as a stream: the whole list as one text (250 000 titles) ran the PC out of memory (1.9.23).
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun loadCatalog(key: String): Catalog? = File(catalogDir, "$key.json.gz").takeIf { it.exists() }?.let { f ->
        runCatching { GZIPInputStream(f.inputStream().buffered(), 64 * 1024).use { json.decodeFromStream(Catalog.serializer(), it) } }.getOrNull()
    }
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun saveCatalog(key: String, c: Catalog) {
        val tmp = File(catalogDir, "$key.tmp")
        GZIPOutputStream(tmp.outputStream(), 64 * 1024).buffered(64 * 1024).use { json.encodeToStream(Catalog.serializer(), c, it) }
        tmp.renameTo(File(catalogDir, "$key.json.gz").also { it.delete() })
    }

    private fun writeSecret(name: String, text: String) {
        val sealed = Crypt32Util.cryptProtectData(text.toByteArray())
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(Base64.getEncoder().encodeToString(sealed))
        val f = File(dir, name); f.delete(); tmp.renameTo(f)
    }

    private fun readSecret(name: String): String? = File(dir, name).takeIf { it.exists() }?.let { f ->
        runCatching { Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(f.readText())).decodeToString() }.getOrNull()
    }
}

/** The profile's settings where there is no AppState at hand (the player, a list's requests). */
object PcSettings { var read: (String, String) -> String = { _, default -> default } }

/** A list's user-agent, else the settings' default. */
val com.lumentv.sync.SyncPlaylist.ua: String get() = userAgent.ifBlank { PcSettings.read("user_agent", "") }
