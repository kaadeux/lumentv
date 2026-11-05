package com.lumentv.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence

@OptIn(ExperimentalSerializationApi::class)
actual fun forEachJsonValue(json: Json, body: HttpBody, block: (JsonElement) -> Unit) {
    for (el in json.decodeToSequence(body.inputStream(), JsonElement.serializer(), DecodeSequenceMode.AUTO_DETECT)) block(el)
}
