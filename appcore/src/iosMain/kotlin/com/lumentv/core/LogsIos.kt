package com.lumentv.core

actual fun logWarn(tag: String, message: String) = println("W/$tag: $message")
