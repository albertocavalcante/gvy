// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncompleteTypeReceiverCompletionTest {
    private val compilation = GroovyCompilationService()
    private val resolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver())

    @AfterEach
    fun close() = compilation.clearCaches()

    private fun complete(source: String, line: Int, character: Int): List<CompletionItem> = runBlocking {
        CompletionProvider.getContextualCompletions(
            uri = "file:///SaveCurrentBuffer.groovy",
            line = line,
            character = character,
            compilationService = compilation,
            semanticResolver = resolver,
            content = source,
        )
    }

    @Test
    fun `incomplete imported type receiver offers usable static methods`() {
        val source = """
            import java.time.Instant

            class SaveCurrentBuffer {
                void run() {
                    Instant.
                }
            }
        """.trimIndent()
        val items = complete(source, line = 4, character = 16)
        val now = items.firstOrNull { it.label == "now" && it.insertText == "now()" }
        assertTrue(now != null, "Missing static now: ${items.map { it.label }}")
        assertEquals(CompletionItemKind.Method, now.kind)
        assertEquals("now()", now.insertText)
        assertFalse(items.any { it.label == "getNano" })
    }

    @Test
    fun `an imported type alias offers static methods`() {
        val source = """
            import java.time.Instant as Moment
            Moment.
        """.trimIndent()
        val items = complete(source, line = 1, character = 7)
        assertTrue(items.any { it.label == "now" && it.insertText == "now()" }, "Missing alias static method")
        assertFalse(items.any { it.label == "getNano" })
    }

    @Test
    fun `imported type receiver offers inherited static methods`() {
        val source = """
            import java.util.GregorianCalendar
            GregorianCalendar.
        """.trimIndent()
        val items = complete(source, line = 1, character = 18)
        assertTrue(
            items.any { it.label == "getInstance" && it.insertText == "getInstance()" },
            "Missing inherited static getInstance: ${items.map { it.label }}",
        )
        assertFalse(items.any { it.label == "getTime" })
    }

    @Test
    fun `incomplete instance receiver keeps instance methods`() {
        val source = """
            import java.time.Instant
            Instant timestamp = Instant.EPOCH
            timestamp.
        """.trimIndent()
        val items = complete(source, line = 2, character = 10)
        assertTrue(items.any { it.label == "getNano" }, "Missing instance getNano: ${items.map { it.label }}")
        assertFalse(items.any { it.label == "now" })
    }

    @Test
    fun `a variable shadowing an imported type remains an instance receiver`() {
        val source = """
            import java.time.Instant
            def Instant = new Date()
            Instant.
        """.trimIndent()
        val items = complete(source, line = 2, character = 8)
        assertTrue(items.any { it.label == "getTime" }, "Missing Date instance method: ${items.map { it.label }}")
        assertFalse(items.any { it.label == "now" })
    }
}
