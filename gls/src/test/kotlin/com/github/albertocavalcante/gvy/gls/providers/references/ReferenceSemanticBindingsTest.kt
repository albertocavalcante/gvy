// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReferenceSemanticBindingsTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `references isolate equally named and typed parameters in different methods`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Uses { String first(String value) { value }; String other(String value) { value } }"
            assertBindings(source, "value", 2)
        }
    }

    @Test
    fun `references from a default expression retain its declaration and body`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Uses { def first(value, copy = value) { value }; def other(value) { value } }"
            assertBindings(source, "value", 3, cursorOrdinal = 1)
        }
    }

    @Test
    fun `references from a memoized body resolve its source parameter`(): Unit = runBlocking {
        supervisorScope {
            val source = """
                class Uses {
                    @groovy.transform.Memoized
                    String first(String value) { value }
                    String other(String value) { value }
                }
            """.trimIndent()
            assertBindings(source, "value", 2, cursorOrdinal = 1, requireTransform = true)
        }
    }

    @Test
    fun `references retain exact UTF16 ranges after supplementary characters`(): Unit = runBlocking {
        supervisorScope {
            val source = "/* 😀😀 */ class Uses { def first(a, b = a) { a + b } }\r\n"
            assertBindings(source, "a", 3, cursorOrdinal = 2)
        }
    }

    private suspend fun CoroutineScope.assertBindings(
        source: String,
        name: String,
        expectedCount: Int,
        cursorOrdinal: Int = 0,
        requireTransform: Boolean = false,
    ) {
        val uri = workspace.resolve("Uses.groovy").toUri()
        Files.writeString(Path.of(uri), source)
        val documents = DocumentProvider().apply { put(uri, source, 41) }
        val compilation = GroovyCompilationService(
            parentClassLoader = javaClass.classLoader,
            documentProvider = documents,
        )
        compilation.workspaceManager.initializeWorkspace(workspace)
        compilation.workspaceManager.updateWorkspaceModel(workspace, emptyList(), listOf(workspace))
        try {
            compilation.compile(uri, source)
            val parsed = assertNotNull(compilation.getParseResult(uri))
            assertNotNull(parsed.ast)
            if (requireTransform) {
                assertTrue(
                    parsed.astModel.getAllNodes().filterIsInstance<VariableExpression>().any {
                        val parameter = it.accessedVariable as? Parameter
                        parameter?.name == name && parameter.lineNumber < 1
                    },
                    "The real Memoized transform must introduce a source-less parameter clone",
                )
            }
            val endpoint = GroovyTextDocumentService(
                this,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val index = TextIndex(source)
            val offsets = Regex("\\b${Regex.escape(name)}\\b").findAll(source).map { it.range.first }.toList()
            for (includeDeclaration in listOf(true, false)) {
                val expectedOffsets = offsets.take(expectedCount).drop(if (includeDeclaration) 0 else 1)
                val expected = expectedOffsets.map {
                    Location(uri.toString(), Range(index.positionOf(it), index.positionOf(it + name.length)))
                }
                val actual = endpoint.references(
                    ReferenceParams(
                        TextDocumentIdentifier(uri.toString()),
                        index.positionOf(offsets[cursorOrdinal]),
                        ReferenceContext(includeDeclaration),
                    ),
                ).await()
                assertEquals(
                    expected,
                    actual.sortedWith(compareBy({ it.range.start.line }, { it.range.start.character })),
                    "includeDeclaration=$includeDeclaration",
                )
            }
        } finally {
            compilation.clearCaches()
        }
    }
}
