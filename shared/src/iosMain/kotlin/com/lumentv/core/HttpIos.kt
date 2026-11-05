@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.lumentv.core

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithBytes
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy

actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

/**
 * iPhone: the shared code's [Http] over NSURLSession (Apple's network: its own DNS, caches and system proxy). The
 * answer is read whole, then given as a stream.
 */
class AppleHttp(private val defaultTimeoutMs: Long = 30_000) : Http {
    override fun execute(request: HttpRequest): HttpResponse {
        val url = NSURL.URLWithString(request.url) ?: throw IOException("Adresse invalide.")
        val req = NSMutableURLRequest.requestWithURL(url)
        req.setHTTPMethod(request.method)
        req.setTimeoutInterval((request.timeoutMs ?: defaultTimeoutMs) / 1000.0)
        request.headers.forEach { (k, v) -> req.setValue(v, forHTTPHeaderField = k) }
        request.body?.let { b ->
            req.setHTTPBody(b.toNSData())
            request.contentType?.let { req.setValue(it, forHTTPHeaderField = "Content-Type") }
        }

        val done = dispatch_semaphore_create(0)
        var data: NSData? = null
        var response: NSURLResponse? = null
        var error: NSError? = null
        NSURLSession.sharedSession.dataTaskWithRequest(req) { d, r, e ->
            data = d; response = r; error = e
            dispatch_semaphore_signal(done)
        }.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)

        error?.let { throw IOException(message(it)) }
        val http = response as? NSHTTPURLResponse ?: throw IOException("Réponse inattendue du serveur.")
        return HttpResponse(http.statusCode.toInt(), { http.valueForHTTPHeaderField(it) }, HttpBody.of(data?.toByteArray() ?: ByteArray(0)))
    }

    /** The same sentences as on the TV (core/Errors.kt) for the usual failures. */
    private fun message(e: NSError): String = when (e.code) {
        NSURLErrorTimedOut -> "Le serveur ne répond pas (délai dépassé)."
        NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed -> "Serveur introuvable. Vérifiez l'adresse et la connexion Internet."
        NSURLErrorCannotConnectToHost -> "Connexion au serveur impossible."
        NSURLErrorNotConnectedToInternet -> "Pas de connexion Internet."
        else -> e.localizedDescription
    }
}

fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
