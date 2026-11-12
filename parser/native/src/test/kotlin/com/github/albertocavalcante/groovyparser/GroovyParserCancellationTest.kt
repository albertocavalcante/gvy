// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroovyParserCancellationTest {

    private val parser = GroovyParserFacade()
    private val uri = URI.create("file:///Sample.groovy")
    private val validClass = "class Sample { String hello() { 'hi' } }"

    private fun request(content: String, phase: Int = Phases.CANONICALIZATION, hook: () -> Unit = {}) =
        ParseRequest(uri = uri, content = content, compilePhase = phase, checkCancelled = hook)

    @Test
    fun `compile stops and propagates when the hook throws after a few phases`() {
        var calls = 0
        assertThrows<CancellationException> {
            parser.parse(
                request(validClass) {
                    calls++
                    if (calls == 3) throw CancellationException("cancelled")
                },
            )
        }
        assertEquals(3, calls, "no further phase may run after the hook threw")
    }

    @Test
    fun `hook is consulted more than once for a deep compile`() {
        var calls = 0
        parser.parse(request(validClass) { calls++ })
        assertTrue(calls > 3, "expected a check per phase, got $calls")
    }

    @Test
    fun `cancelled before the first phase does no compile work`() {
        var calls = 0
        assertThrows<CancellationException> {
            parser.parse(
                request(validClass) {
                    calls++
                    throw CancellationException("cancelled")
                },
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun `cancellation is not swallowed by the retry paths`() {
        // Unresolved superclass triggers the CONVERSION retry; cancel once the retry starts.
        val content = "class Sample extends does.not.Exist { }"
        var calls = 0
        var baseline = 0
        parser.parse(request(content) { baseline++ })
        assertTrue(baseline > 0)
        assertThrows<CancellationException> {
            parser.parse(
                request(content) {
                    calls++
                    if (calls > baseline - 1) throw CancellationException("cancelled")
                },
            )
        }
    }

    @Test
    fun `a never-cancelling hook gives the same result as the default`() {
        val inputs = listOf(
            validClass,
            "class Broken { void f( { }",
            "class Sample { UnknownType x }",
            "class Sample extends does.not.Exist { }",
            "println 'script'",
        )
        for (content in inputs) {
            val plain = parser.parse(ParseRequest(uri = uri, content = content, compilePhase = Phases.CANONICALIZATION))
            val hooked = parser.parse(request(content) {})
            assertEquals(plain.diagnostics, hooked.diagnostics, content)
            assertEquals(
                plain.ast?.classes?.map { it.name to it.superClass?.name },
                hooked.ast?.classes?.map { it.name to it.superClass?.name },
                content,
            )
        }
    }
}
