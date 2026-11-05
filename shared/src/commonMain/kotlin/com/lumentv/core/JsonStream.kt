package com.lumentv.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The JSON values of a body one after another: the items of a top-level array, or the values themselves. On the TV
 * (JVM) the body is read as a stream (a 30 000-film list never sits whole in memory); on the iPhone it is parsed whole.
 */
expect fun forEachJsonValue(json: Json, body: HttpBody, block: (JsonElement) -> Unit)
