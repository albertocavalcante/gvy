// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignatureHelpIncompleteTest {
    private val documents = DocumentProvider()
    private val compilation = GroovyCompilationService(documentProvider = documents)
    private val provider = SignatureHelpProvider(
        compilation,
        documents,
        SemanticTypeResolver(compilation.classpathService.getTypeSolver()),
    )

    @AfterEach
    fun clearCompilationCaches() = compilation.clearCaches()

    @Test
    fun `signature help uses incomplete current buffer after a different source was compiled for the uri`(): Unit =
        runTest {
            val uri = URI.create("file:///SignatureHelpCurrentSnapshot.groovy")
            val previousSource = """
                class Example {
                    def run() {
                        staleOnly("old")
                    }
                    def staleOnly(String value) {}
                }
            """.trimIndent()
            val currentSource = """
                class Example {
                    def run() {
                        println(
            """.trimIndent()

            documents.put(uri, previousSource, version = 1)
            compilation.compile(uri, previousSource)
            documents.put(uri, currentSource, version = 2)

            val position = positionAfter(currentSource, "println(")
            val result = provider.provideSignatureHelp(uri.toString(), position)
            val labels = result.signatures.map { it.label }

            assertTrue(labels.any { it.startsWith("void println") }, "Expected println signatures, got: $labels")
            assertFalse(labels.any { it.contains("staleOnly") }, "Stale compiled call leaked into help: $labels")
            assertEquals(0, result.activeParameter)
        }

    @Test
    fun `signature help recovers incomplete calls at eof and before class closers`(): Unit = runTest {
        val sources = listOf(
            """
                class Example {
                    def run() {
                        println(
            """.trimIndent(),
            """
                class Example {
                    def run() {
                        println(
                    }
                }
            """.trimIndent(),
        )

        sources.forEachIndexed { index, source ->
            val uri = URI.create("file:///SignatureHelpFreshIncomplete$index.groovy")
            documents.put(uri, source, version = 1)

            val result = provider.provideSignatureHelp(uri.toString(), positionAfter(source, "println("))
            val labels = result.signatures.map { it.label }

            assertTrue(labels.any { it.startsWith("void println") }, "Expected println signatures, got: $labels")
            assertEquals(0, result.activeParameter)
        }
    }

    @Test
    fun `signature help resolves explicit receivers from the transient current module`(): Unit = runTest {
        val uri = URI.create("file:///SignatureHelpCurrentReceiver.groovy")
        val previousSource = """
            class Example {
                def run() {
                    def text = 1
                    text.toString()
                }
            }
        """.trimIndent()
        val currentSource = """
            class Example {
                def run() {
                    def text = "}value{"
                    text.substring(
                }
            }
        """.trimIndent()
        documents.put(uri, previousSource, version = 1)
        compilation.compile(uri, previousSource)
        documents.put(uri, currentSource, version = 2)

        val result = provider.provideSignatureHelp(uri.toString(), positionAfter(currentSource, "substring("))
        val labels = result.signatures.map { it.label }

        assertTrue(
            labels.any { it.startsWith("String substring") },
            "Expected String receiver signatures, got: $labels",
        )
    }

    @Test
    fun `signature help does not recover call shaped text inside comments or strings`(): Unit = runTest {
        val examples = listOf(
            """
                class Example {
                    def run() {
                        "println("
                    }
                }
            """.trimIndent(),
            """
                class Example {
                    def run() {
                        def message = "prefix ${'$'}{'println('}"
                    }
                }
            """.trimIndent(),
            """
                class Example {
                    def run() {
                        // println(
                    }
                }
            """.trimIndent(),
            """
                class Example {
                    def run() {
                        "unclosed println(
            """.trimIndent(),
            "class Example {\n    def run() {\n        \"\"\"unclosed println(\n",
            """
                class Example {
                    def run() {
                        /* unclosed println(
            """.trimIndent(),
            """
                class Example {
                    def run() {
                        /println(/
                    }
                }
            """.trimIndent(),
            """
                class Example {
                    def run() {
                        $/println(/$
                    }
                }
            """.trimIndent(),
        )

        examples.forEachIndexed { index, source ->
            val uri = URI.create("file:///SignatureHelpLiteralContext$index.groovy")
            documents.put(uri, source, version = 1)

            val result = provider.provideSignatureHelp(uri.toString(), positionAfter(source, "println("))

            assertTrue(result.signatures.isEmpty(), "Call shaped text in a literal context must not recover")
        }
    }

    private fun positionAfter(source: String, snippet: String): Position {
        val lineIndex = source.lines().indexOfFirst { snippet in it }
        check(lineIndex >= 0) { "Snippet '$snippet' not found" }
        val line = source.lines()[lineIndex]
        return Position(lineIndex, line.indexOf(snippet) + snippet.length)
    }
}
