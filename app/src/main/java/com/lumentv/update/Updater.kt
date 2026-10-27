package com.lumentv.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.lumentv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** Contents of version.json in the public releases repository. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val sha256: String = "",
    val notes: String = "",
)

/**
 * Checks the update feed (`lumen.updateUrl`, a version.json next to the APK), downloads a newer APK and hands it to
 * the system installer. Android only accepts it when it is signed with the same key, so a tampered file cannot
 * replace the app; the SHA-256 from the feed also catches a corrupted download.
 */
class Updater(private val context: android.content.Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The newer version found by the last check, shown as a prompt until dismissed. */
    val available = MutableStateFlow<UpdateInfo?>(null)

    val enabled get() = BuildConfig.UPDATE_URL.isNotEmpty()

    /** @return the newer version, or null when up to date (or the feed is unreachable: [Result.failure]). */
    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext Result.success(null)
        runCatching {
            // A query string defeats intermediate caches (raw.githubusercontent.com caches a few minutes).
            val req = Request.Builder().url(BuildConfig.UPDATE_URL + "?t=" + System.currentTimeMillis() / 60_000).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                json.decodeFromString<UpdateInfo>(r.body!!.string())
            }.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }.onSuccess { available.value = it }.onFailure { Log.w("Updater", "check failed: $it") }
    }

    /** Downloads the APK (reused when already complete). [onProgress] gets 0..100. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, "LumenTV-${info.versionCode}.apk")
        if (file.exists() && (info.sha256.isEmpty() || sha256(file) == info.sha256.lowercase())) return@withContext file
        dir.listFiles()?.forEach { it.delete() }
        http.newCall(Request.Builder().url(info.apk).build()).execute().use { r ->
            if (!r.isSuccessful) error("Téléchargement refusé (HTTP ${r.code})")
            val body = r.body!!
            val total = body.contentLength()
            val tmp = File(dir, "partial.apk")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            if (info.sha256.isNotEmpty() && sha256(tmp) != info.sha256.lowercase()) {
                tmp.delete(); error("Fichier de mise à jour abîmé, réessayez.")
            }
            tmp.renameTo(file)
        }
        file
    }

    /** Whether Android lets this app open the installer (the "install unknown apps" permission, Android 8+). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the screen where the viewer allows Lumen TV to install updates. */
    fun askInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        runCatching { activity.startActivity(intent) }
            .recoverCatching { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }

    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
