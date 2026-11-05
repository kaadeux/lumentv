package com.lumentv.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

actual fun forEachJsonValue(json: Json, body: HttpBody, block: (JsonElement) -> Unit) {
    val text = body.readAll().decodeToString().trim()
    if (text.isEmpty()) return
    when (val root = json.parseToJsonElement(text)) {
        is JsonArray -> root.forEach(block)
        else -> block(root)
    }
}
