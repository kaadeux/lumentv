package com.lumentv.core

/** iPhone: AppleHttp already words the network failures in French (no URL in them). */
internal actual fun platformMessage(t: Throwable): String? = if (t is IOException && t !is HttpStatusException) t.message else null
