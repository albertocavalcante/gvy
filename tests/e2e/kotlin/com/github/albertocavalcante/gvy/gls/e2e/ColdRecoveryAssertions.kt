// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokensLegend
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Authored source and positions, independent of the parser under evaluation. */
internal class ColdRecoveryFixture(tag: String, val malformed: Boolean, newline: String) {
    private val member = if (malformed) "" else "NEW"
    val before = "Before$tag"
    val after = "After$tag"
    val text = listOf(
        "enum OrderStatus { NEW, PAID, SHIPPED, CANCELLED }",
        "class Order {}",
        "class OrderBuilder {",
        "    OrderBuilder status(OrderStatus value) { this }",
        "    Order build() { new Order() }",
        "}",
        "class $before {}",
        "class OrderService {",
        "    Order place() {",
        "        new OrderBuilder()",
        "            .status(OrderStatus.$member)",
        "            .build()",
        "    }",
        "}",
        "class $after {}",
        "",
    ).joinToString(newline)

    fun nameRange(name: String): Range {
        val line = text.lineSequence().withIndex().single { "class $name {}" == it.value }
        return Range(Position(line.index, 6), Position(line.index, 6 + name.length))
    }

    fun declarationRange(name: String): Range {
        val span = nameRange(name)
        return Range(Position(span.start.line, 0), Position(span.start.line, "class $name {}".length))
    }

    val completionPosition: Position get() {
        val line = text.lineSequence().withIndex().single { ".status(OrderStatus." in it.value }
        return Position(line.index, line.value.indexOf("OrderStatus.") + "OrderStatus.".length)
    }
}

internal object ColdRecoveryAssertions {
    fun tokens(
        result: JsonElement,
        legend: SemanticTokensLegend,
        source: String,
        encoding: String?,
    ): List<DecodedSemanticToken> {
        if (result == JsonNull) return emptyList()
        val data = result.jsonObject.getValue("data").jsonArray.map { it.jsonPrimitive.int }
        return SemanticTokenDecoder.decode(data, legend, source, encoding)
    }

    fun token(tokens: List<DecodedSemanticToken>, fixture: ColdRecoveryFixture, name: String) {
        val matches = tokens.filter { it.text == name }
        if (matches.isEmpty()) throw AssertionError("recovery: missing class token $name")
        assertEquals(1, matches.size, "Duplicate current class token")
        val token = matches.single()
        assertEquals(fixture.nameRange(name), token.range, "Current class token range")
        assertEquals("class", token.type, "Current class token kind")
        assertTrue("declaration" in token.modifiers, "Class declaration modifier")
    }

    fun hover(result: JsonElement, fixture: ColdRecoveryFixture, name: String) {
        if (result == JsonNull) throw AssertionError("recovery: null class hover $name")
        val hover = result.jsonObject
        val contents = hover.getValue("contents")
        val value = if (contents is JsonArray) {
            contents.joinToString(
                "\n",
                transform = ::hoverText,
            )
        } else {
            hoverText(contents)
        }
        if (value.isEmpty()) throw AssertionError("recovery: empty class hover $name")
        assertTrue(Regex("\\bclass\\s+${Regex.escape(name)}\\b").containsMatchIn(value), "Current hover identity")
        val span = assertNotNull(hover["range"], "Current hover must supply its source range")
        assertEquals(fixture.nameRange(name), range(span), "Current hover range")
    }

    fun outline(result: JsonElement, fixture: ColdRecoveryFixture, name: String) {
        if (result == JsonNull) throw AssertionError("recovery: missing outline class $name")
        val matches = result.jsonArray.filter { it.jsonObject["name"]?.jsonPrimitive?.content == name }
        if (matches.isEmpty()) throw AssertionError("recovery: missing outline class $name")
        assertEquals(1, matches.size, "Duplicate outline class")
        val symbol = matches.single().jsonObject
        assertEquals(5, symbol.getValue("kind").jsonPrimitive.int, "Outline class kind")
        val declaration = fixture.declarationRange(name)
        assertEquals(declaration, range(symbol.getValue("range")), "Current declaration range")
        val selected = range(symbol.getValue("selectionRange"))
        val nameRange = fixture.nameRange(name)
        assertEquals(declaration.start.line, selected.start.line)
        assertEquals(declaration.end.line, selected.end.line)
        assertTrue(selected.start.character in declaration.start.character..nameRange.start.character)
        assertTrue(selected.end.character in nameRange.end.character..declaration.end.character)
    }

    fun completion(result: JsonElement) {
        if (result == JsonNull) throw AssertionError("recovery: missing enum completion")
        val items = if (result is JsonArray) result else result.jsonObject.getValue("items").jsonArray
        val labels = items.map { it.jsonObject.getValue("label").jsonPrimitive.content }.toSet()
        val expected = setOf("NEW", "PAID", "SHIPPED", "CANCELLED")
        assertTrue(labels.containsAll(expected), "Missing declared enum members: ${expected - labels}")
    }

    fun diagnostics(result: JsonObject, source: ColdRecoverySource) {
        assertEquals(source.uri, result.getValue("uri").jsonPrimitive.content)
        assertEquals(source.version, result.getValue("version").jsonPrimitive.int)
        assertEquals("true", result.getValue("complete").jsonPrimitive.content)
        assertTrue(result.getValue("failures").jsonArray.isEmpty(), "Diagnostic provider failure")
        val lines = source.text.lineSequence().toList()
        for (diagnostic in result.getValue("diagnostics").jsonArray) {
            val span = range(diagnostic.jsonObject.getValue("range"))
            assertTrue(span.start.line in lines.indices && span.end.line in lines.indices)
            assertTrue(span.start.character in 0..lines[span.start.line].length)
            assertTrue(span.end.character in 0..lines[span.end.line].length)
            assertTrue(
                span.end.line > span.start.line ||
                    (span.end.line == span.start.line && span.end.character >= span.start.character),
            )
        }
    }

    private fun hoverText(contents: JsonElement): String {
        val text = if (contents is JsonObject) contents.getValue("value") else contents
        require(text is JsonPrimitive && text.isString) { "Invalid hover contents" }
        return text.content
    }

    private fun range(element: JsonElement): Range {
        fun position(key: String): Position {
            val value = element.jsonObject.getValue(key).jsonObject
            return Position(value.getValue("line").jsonPrimitive.int, value.getValue("character").jsonPrimitive.int)
        }
        return Range(position("start"), position("end"))
    }
}
