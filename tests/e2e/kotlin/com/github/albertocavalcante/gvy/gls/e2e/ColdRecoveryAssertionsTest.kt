// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokensLegend
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ColdRecoveryAssertionsTest {
    private val fixture = ColdRecoveryFixture("Current", "", "\n")
    private val name = fixture.before
    private val span = fixture.nameRange(name)
    private val token = DecodedSemanticToken(name, span, "class", setOf("declaration"))

    @Test
    fun `current tokens reject stale identity kind range and missing declaration`() {
        ColdRecoveryAssertions.token(listOf(token), fixture, name)
        val mutations = listOf(
            token.copy(text = "BeforeOld"),
            token.copy(type = "variable"),
            token.copy(range = Range(Position(0, 0), Position(0, name.length))),
            token.copy(modifiers = emptySet()),
        )
        for (mutation in mutations) {
            assertFailsWith<AssertionError> { ColdRecoveryAssertions.token(listOf(mutation), fixture, name) }
        }
    }

    @Test
    fun `hover requires current identity and exact source range`() {
        val correct = Json.parseToJsonElement(
            """{"contents":{"kind":"markdown","value":"public class $name"},"range":${span.json()}}""",
        )
        ColdRecoveryAssertions.hover(correct, fixture, name)
        for (mutated in listOf(
            correct.toString().replace(name, "BeforeOld"),
            correct.toString().replace("\"line\":6", "\"line\":0"),
        )) {
            assertFailsWith<AssertionError> {
                ColdRecoveryAssertions.hover(Json.parseToJsonElement(mutated), fixture, name)
            }
        }
    }

    @Test
    fun `outline rejects stale declarations and wrong enclosing ranges`() {
        val range = fixture.declarationRange(name).json()
        val correct = """[{"name":"$name","kind":5,"range":$range,"selectionRange":$range}]"""
        ColdRecoveryAssertions.outline(Json.parseToJsonElement(correct), fixture, name)
        for (mutated in listOf(
            correct.replace(name, "BeforeOld"),
            correct.replace("\"line\":6", "\"line\":0"),
        )) {
            assertFailsWith<AssertionError> {
                ColdRecoveryAssertions.outline(Json.parseToJsonElement(mutated), fixture, name)
            }
        }
    }

    @Test
    fun `successful null feature results become named product assertions`() {
        val tokens = ColdRecoveryAssertions.tokens(JsonNull, SemanticTokensLegend(emptyList(), emptyList()), "", null)
        assertFailsWith<AssertionError> { ColdRecoveryAssertions.token(tokens, fixture, name) }
        assertFailsWith<AssertionError> { ColdRecoveryAssertions.hover(JsonNull, fixture, name) }
        assertFailsWith<AssertionError> { ColdRecoveryAssertions.outline(JsonNull, fixture, name) }
        assertFailsWith<AssertionError> { ColdRecoveryAssertions.completion(JsonNull) }
    }

    @Test
    fun `diagnostic integrity rejects reversed multiline ranges`() {
        val source = ColdRecoverySource("file:///Probe.groovy", 1, fixture.text)
        val reversed = """{"start":{"line":6,"character":0},"end":{"line":5,"character":1}}"""
        val result = Json.parseToJsonElement(
            """{"uri":"file:///Probe.groovy","version":1,"complete":true,"failures":[],""" +
                """"diagnostics":[{"range":$reversed}]}""",
        ).jsonObject
        assertFailsWith<AssertionError> { ColdRecoveryAssertions.diagnostics(result, source) }
    }

    @Test
    fun `Object or GDK completion cannot substitute for the declared enum members`() {
        val actual = Json.parseToJsonElement(
            """{"items":[{"label":"NEW"},{"label":"PAID"},{"label":"SHIPPED"},{"label":"CANCELLED"}]}""",
        )
        ColdRecoveryAssertions.completion(actual)
        assertFailsWith<AssertionError> {
            ColdRecoveryAssertions.completion(Json.parseToJsonElement("""{"items":[{"label":"asBoolean"}]}"""))
        }
    }

    private fun Range.json(): String = JsonBridge.toJsonElement(this).toString()
}
