// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RenameSemanticBindingsTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `explicit it rename preserves earlier and nested implicit closure bindings`(): Unit = runBlocking {
        supervisorScope {
            for (declaration in listOf("String call(String it) { it }", "def chosen = { String it -> it }")) {
                val source = "class Cases { def earlier = { [1].collect { it } }; $declaration }"
                fixture(source).use { fixture ->
                    fixture.assertRename(
                        source.lastIndexOf("it"),
                        "renamed",
                        source.replace(declaration, declaration.replace(Regex("\\bit\\b"), "renamed")),
                    )
                }
            }
        }
    }

    @Test
    fun `parameter rename updates its default argument references`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Defaults { def call(a, b = a) { a } }"
            fixture(source).use { fixture ->
                fixture.assertRename(source.indexOf("a, b"), "renamed", source.replace(Regex("\\ba\\b"), "renamed"))
            }
        }
    }

    @Test
    fun `rename from a default expression selects its referenced parameter`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Defaults { def call(a, b = a) { a } }"
            fixture(source).use { fixture ->
                fixture.assertRename(source.indexOf("= a") + 2, "renamed", source.replace(Regex("\\ba\\b"), "renamed"))
            }
        }
    }

    @Test
    fun `memoized parameter rename retains its body and isolates another method`(): Unit = runBlocking {
        supervisorScope {
            val source = """
                class MemoCases {
                    @groovy.transform.Memoized
                    String one(String value) { value }
                    @groovy.transform.Memoized
                    String two(String value) { value }
                }
            """.trimIndent()
            fixture(source).use { fixture ->
                val method = assertNotNull(fixture.parsed.ast).classes.single { it.name == "MemoCases" }
                    .getDeclaredMethods("one").single()
                val parameter = method.parameters.single()
                val bodyOffset = source.indexOf("{ value }") + 2
                val bodyPosition = TextIndex(source).positionOf(bodyOffset)
                val body = fixture.parsed.astModel.getAllNodes().filterIsInstance<VariableExpression>().single {
                    it.name == "value" && it.lineNumber == bodyPosition.line + 1 &&
                        it.columnNumber == bodyPosition.character + 1
                }
                assertTrue(body.accessedVariable is Parameter, "The transformed body must retain a parameter binding")
                assertTrue(body.accessedVariable !== parameter, "The actual Memoized transform must create its clone")

                fixture.assertRename(
                    source.indexOf("value"),
                    "renamed",
                    source.replace("String one(String value) { value }", "String one(String renamed) { renamed }"),
                )
            }
        }
    }

    @Test
    fun `rename from a memoized body resolves the original declaration`(): Unit = runBlocking {
        supervisorScope {
            val source = """
                class MemoCases {
                    @groovy.transform.Memoized
                    String join(String prefix, String value = prefix) { prefix + value }
                    String other(String prefix, String value) { prefix + value }
                }
            """.trimIndent()
            fixture(source).use { fixture ->
                fixture.assertRename(
                    source.indexOf("prefix + value"),
                    "renamed",
                    source.replace(
                        "String join(String prefix, String value = prefix) { prefix + value }",
                        "String join(String renamed, String value = renamed) { renamed + value }",
                    ),
                )
            }
        }
    }

    @Test
    fun `memoized body parameter rename preserves closure bindings and another method`(): Unit = runBlocking {
        supervisorScope {
            val source = """
                class MemoCases {
                    @groovy.transform.Memoized
                    String join(String value) { [value].collect { String item -> item + value }.join() }
                    String other(String value) { value }
                }
            """.trimIndent()
            fixture(source).use { fixture ->
                fixture.assertRename(
                    source.indexOf("value].collect"),
                    "renamed",
                    source.replace(
                        "String join(String value) { [value].collect { String item -> item + value }.join() }",
                        "String join(String renamed) { [renamed].collect { String item -> item + renamed }.join() }",
                    ),
                )
            }
        }
    }

    @Test
    fun `memoized overloads retain parameter ordinals and UTF16 source positions`(): Unit = runBlocking {
        supervisorScope {
            val source = """
                class MemoCases {
                    @groovy.transform.Memoized /* 😀 */ String join(String prefix, String value) { prefix + value }
                    @groovy.transform.Memoized
                    String join(String value) { value }
                }
            """.trimIndent().replace("\n", "\r\n")
            fixture(source).use { fixture ->
                fixture.assertRename(
                    source.indexOf("+ value") + 2,
                    "renamed",
                    source.replace(
                        "String join(String prefix, String value) { prefix + value }",
                        "String join(String prefix, String renamed) { prefix + renamed }",
                    ),
                )
            }
        }
    }

    @Test
    fun `rename selects a one letter parameter after supplementary characters`(): Unit = runBlocking {
        supervisorScope {
            val source = "/* 😀😀 */ class UnicodeBindings { def call(a, b = a) { a + b } }"
            fixture(source).use { fixture ->
                fixture.assertRename(
                    source.indexOf("a + b"),
                    "renamed",
                    source.replace(Regex("\\ba\\b"), "renamed"),
                )
            }
        }
    }

    @Test
    fun `memoized vararg parameter rename preserves its type and another method`(): Unit = runBlocking {
        supervisorScope {
            val source = """
                class MemoCases {
                    @groovy.transform.Memoized
                    String join(String... value) { value.join() }
                    String other(String... value) { value.join() }
                }
            """.trimIndent()
            fixture(source).use { fixture ->
                fixture.assertRename(
                    source.indexOf("{ value") + 2,
                    "renamed",
                    source.replace(
                        "String join(String... value) { value.join() }",
                        "String join(String... renamed) { renamed.join() }",
                    ),
                )
            }
        }
    }

    @Test
    fun `rename rejects a conversion phase cache entry before resolving bindings`(): Unit = runBlocking {
        supervisorScope {
            fixture(fieldSource(), phase = Phases.CONVERSION).use { fixture ->
                assertEquals(Phases.CONVERSION, fixture.parsed.outcome.achievedPhase)

                val error = assertFailsWith<ResponseErrorException> { fixture.renameField() }

                assertEquals(ResponseErrorCode.InvalidRequest.value, error.responseError.code)
            }
        }
    }

    @Test
    fun `rename rejects an automatic conversion fallback from a broken source root`(): Unit = runBlocking {
        supervisorScope {
            fixture(fieldSource(withBroken = true), layout = Layout.UNLISTED_BROKEN).use { fixture ->
                assertEquals(Fallback.COMPILATION_FAILED, fixture.parsed.outcome.fallback)
                assertEquals(Phases.CONVERSION, fixture.parsed.outcome.achievedPhase)

                val error = assertFailsWith<ResponseErrorException> { fixture.renameField() }

                assertEquals(ResponseErrorCode.InvalidRequest.value, error.responseError.code)
            }
        }
    }

    @Test
    fun `rename permits semantic results with an unrelated excluded source`(): Unit = runBlocking {
        supervisorScope {
            val source = fieldSource()
            fixture(source, layout = Layout.LISTED_BROKEN).use { fixture ->
                assertTrue(fixture.parsed.outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS)
                assertTrue(fixture.parsed.outcome.isDegraded)
                assertTrue(fixture.parsed.outcome.excludedSources.any { it.source.endsWith("Broken.groovy") })

                fixture.assertRename(
                    source.indexOf("value"),
                    "renamed",
                    source.replace("private String value", "private String renamed")
                        .replace("def read() { value }", "def read() { renamed }"),
                )
            }
        }
    }

    private fun fieldSource(withBroken: Boolean = false): String = "class Uses {\n" +
        (if (withBroken) "    Broken dependency\n" else "") +
        "    private String value\n    def read() { value }\n    def other(String value) { value }\n}\n"

    private enum class Layout { CLEAN, LISTED_BROKEN, UNLISTED_BROKEN }

    private suspend fun CoroutineScope.fixture(
        source: String,
        phase: Int = Phases.CANONICALIZATION,
        layout: Layout = Layout.CLEAN,
    ): Fixture {
        val broken = workspace.resolve("Broken.groovy")
        if (layout == Layout.LISTED_BROKEN) Files.writeString(broken, "class Broken { void m( }")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(
            parentClassLoader = javaClass.classLoader,
            documentProvider = documents,
        )
        compilation.workspaceManager.initializeWorkspace(workspace)
        compilation.workspaceManager.updateWorkspaceModel(workspace, emptyList(), listOf(workspace))
        if (layout == Layout.UNLISTED_BROKEN) Files.writeString(broken, "class Broken { void m( }")
        val uri = workspace.resolve("Target.groovy").toUri()
        Files.writeString(Path.of(uri), source)
        documents.put(uri, source, 41)
        val endpoint = GroovyTextDocumentService(
            this,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        compilation.compile(uri, source, phase)
        val parsed = assertNotNull(compilation.getParseResult(uri))
        assertNotNull(parsed.ast)
        return Fixture(uri, source, compilation, parsed, endpoint)
    }

    private data class Fixture(
        val uri: URI,
        val source: String,
        val compilation: GroovyCompilationService,
        val parsed: ParseResult,
        val endpoint: GroovyTextDocumentService,
    ) : AutoCloseable {
        suspend fun renameField() = rename(source.indexOf("value"), "renamed")

        suspend fun rename(offset: Int, replacement: String) = endpoint.rename(
            RenameParams(TextDocumentIdentifier(uri.toString()), TextIndex(source).positionOf(offset), replacement),
        ).await()

        suspend fun assertRename(offset: Int, replacement: String, expected: String) {
            val result = rename(offset, replacement)
            assertTrue(result.documentChanges.all { it.isLeft }, "Identifier rename must not rename a file")
            assertEquals(1, result.documentChanges.size)
            val change = result.documentChanges.single().left
            assertEquals(41, change.textDocument.version)
            val index = TextIndex(source)
            val edits = change.edits.sortedBy { index.offsetOf(it.range.start) }
            val spans = edits.map { index.offsetOf(it.range.start) to index.offsetOf(it.range.end) }
            assertTrue(spans.all { (start, end) -> start < end && end <= source.length })
            assertTrue(spans.zipWithNext().all { (left, right) -> left.second <= right.first })
            val actual = edits.asReversed().fold(source) { text, edit ->
                val start = index.offsetOf(edit.range.start)
                val end = index.offsetOf(edit.range.end)
                text.substring(0, start) + edit.newText + text.substring(end)
            }
            assertEquals(expected, actual)
        }

        override fun close() = compilation.clearCaches()
    }
}
