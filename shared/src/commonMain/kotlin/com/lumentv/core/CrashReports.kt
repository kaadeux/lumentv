package com.lumentv.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * Crash reports, the same on every app (TV, phone, iPhone, PC): the error is written to a file the moment the app
 * crashes, then sent at the next start to the sync server, where the developer reads it. Nothing personal leaves the
 * device: accounts, passwords and server addresses are masked, the list's content is not sent.
 */
object CrashReports {
    /** Reports kept on the device while they cannot be sent (no network): the latest ones. */
    private const val KEEP = 5
    private const val TRACE = 40_000

    /** What the reports say about the app and the device. */
    class App(val app: String, val version: String, val device: String, val os: String)

    /** Whose device it is: the online profile signed in ("flo", the parents'), set by the apps once it is known. */
    @kotlin.concurrent.Volatile var profile: String = ""

    /** This device among those of a profile: the first letters of its random id (the same in every report). */
    @kotlin.concurrent.Volatile var deviceId: String = ""

    /** The screen in use, set by the apps as the viewer moves: it says where the crash happened. */
    @kotlin.concurrent.Volatile var where: String = ""

    private val address = Regex("(?i)\\b(https?|rtmp|rtsp|udp)://[^\\s\"')]+")
    private val ip = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b")

    /** A text without accounts nor addresses: "http://server:8080/live/user/pass/1.ts" → "<adresse>". */
    fun mask(text: String): String = Redact.text(text).replace(address, "<adresse>").replace(ip, "<ip>")

    /** The report of [t] as it is sent. */
    fun report(t: Throwable, app: App, time: Long = nowMillis(), where: String = this.where): JsonObject = buildJsonObject {
        put("app", app.app); put("version", app.version); put("device", app.device); put("os", app.os)
        put("profile", profile); put("deviceId", deviceId.take(8))
        put("time", time); put("where", where)
        put("error", mask("${t::class.simpleName ?: "Erreur"}: ${t.message.orEmpty()}").take(500))
        put("trace", mask(t.stackTraceToString()).take(TRACE))
    }

    /** Writes the report of [t] at once (the app is about to close); kept until it is sent. Never throws. */
    fun record(file: TextFile, t: Throwable, app: App) {
        runCatching {
            val old = runCatching { file.read()?.let { Json.parseToJsonElement(it).jsonArray.toList() } }.getOrNull().orEmpty()
            file.write(JsonArray((old + report(t, app)).takeLast(KEEP)).toString())
        }
    }

    /** A report the system kept (Android's exit reasons, the iPhone's MetricKit): sent the same way. */
    fun recordText(file: TextFile, error: String, trace: String, app: App, time: Long, where: String = "") {
        runCatching {
            val old = runCatching { file.read()?.let { Json.parseToJsonElement(it).jsonArray.toList() } }.getOrNull().orEmpty()
            val r = buildJsonObject {
                put("app", app.app); put("version", app.version); put("device", app.device); put("os", app.os)
        put("profile", profile); put("deviceId", deviceId.take(8))
                put("time", time); put("where", where); put("error", mask(error).take(500)); put("trace", mask(trace).take(TRACE))
            }
            file.write(JsonArray((old + r).takeLast(KEEP)).toString())
        }
    }

    /** Sends the reports waiting in [file] to the sync server; those sent leave the file. Never throws. */
    fun send(file: TextFile, http: Http, syncUrl: String) {
        if (syncUrl.isBlank()) return
        val pending = runCatching { file.read()?.let { Json.parseToJsonElement(it).jsonArray.toList() } }.getOrNull().orEmpty()
        if (pending.isEmpty()) return
        val left = pending.filterNot { r ->
            runCatching {
                http.execute(HttpRequest(syncUrl.trimEnd('/') + "/v1/crash", "POST", body = r.toString().encodeToByteArray(),
                    contentType = "application/json", timeoutMs = 15_000)).use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (left.isEmpty()) file.delete() else file.write(JsonArray(left).toString())
    }

    /** The file of the reports waiting to be sent, in the app's own folder. */
    fun fileIn(dir: String) = TextFile("$dir/crash-reports.json")
}
