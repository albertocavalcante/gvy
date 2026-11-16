// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertTrue

internal object WarmRecoveryAssertions {
    fun completion(result: JsonElement, marker: String, previousMarkers: Set<String>, prefix: Boolean) {
        if (result == JsonNull) throw AssertionError("recovery: missing warm enum completion $marker")
        val items = if (result is JsonArray) result else result.jsonObject.getValue("items").jsonArray
        val labels = items.map { it.jsonObject.getValue("label").jsonPrimitive.content }.toSet()
        val expected = if (prefix) {
            setOf("NEW", marker)
        } else {
            setOf("NEW", "PAID", "SHIPPED", "CANCELLED", marker)
        }
        assertTrue(labels.containsAll(expected), "Missing current enum members: ${expected - labels}")
        assertTrue(labels.intersect(previousMarkers).isEmpty(), "Stale warm enum marker")
    }
}
