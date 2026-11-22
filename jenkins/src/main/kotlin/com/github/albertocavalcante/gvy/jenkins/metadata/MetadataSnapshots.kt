// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Collections

/** Read-only Kotlin interfaces alone do not detach or protect mutable JVM collections. */
internal fun <T> List<T>.immutableSnapshot(): List<T> = Collections.unmodifiableList(ArrayList(this))

internal fun JsonObject.immutableSnapshot(): JsonObject = JsonObject(
    Collections.unmodifiableMap(entries.associate { (key, value) -> key to value.immutableJsonSnapshot() }),
)

private fun JsonElement.immutableJsonSnapshot(): JsonElement = when (this) {
    is JsonObject -> immutableSnapshot()
    is JsonArray -> JsonArray(map { it.immutableJsonSnapshot() }.immutableSnapshot())
    else -> this
}
