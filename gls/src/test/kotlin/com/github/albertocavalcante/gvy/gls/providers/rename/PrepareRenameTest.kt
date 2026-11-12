// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrepareRenameTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `prepare preserves bare CR parameter declaration and usage coordinates`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Cases {\r String call(String value) { value }\r}\r"
            fixture(source).use {
                it.assertPrepared(source.indexOf("value"), "value")
                it.assertPrepared(source.lastIndexOf("value"), "value")
            }
        }
    }

    @Test
    fun `prepare returns the exact usage range and original parameter name`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Cases { String call(String value) { value } }"
            fixture(source).use { it.assertPrepared(source.lastIndexOf("value"), "value") }
        }
    }

    @Test
    fun `prepare preserves UTF16 and CRLF coordinates for defaulted bindings`(): Unit = runBlocking {
        supervisorScope {
            val source = "/* 😀😀 */ class Cases { def call(a, copy = a) { a } }\r\n"
            fixture(source).use { it.assertPrepared(source.lastIndexOf("a"), "a") }
        }
    }

    @Test
    fun `prepare resolves a memoized body parameter without editing the document`(): Unit = runBlocking {
        supervisorScope {
            val source = """
                class Cases {
                    @groovy.transform.Memoized
                    String call(String value) { value }
                }
            """.trimIndent()
            fixture(source).use { it.assertPrepared(source.lastIndexOf("value"), "value") }
        }
    }

    @Test
    fun `prepare does not offer an enclosing symbol on whitespace`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Cases {  String call(String value) { value } }"
            fixture(source).use { assertNull(it.prepare(source.indexOf("{  ") + 2)) }
        }
    }

    @Test
    fun `prepare does not offer names inside comments or strings`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Cases { def value = 'value'; def call() { /* value */ 'value' } }"
            fixture(source).use {
                assertNull(it.prepare(source.indexOf("/* value") + 3))
                assertNull(it.prepare(source.lastIndexOf("value")))
            }
        }
    }

    @Test
    fun `prepare accepts equivalent URI spellings`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Cases { def call(value) { value } }"
            fixture(source).use {
                val alternate = URI("file", null, it.uri.path, null).toString()
                assertTrue(alternate != it.uri.toString())
                val result = assertNotNull(it.prepare(source.lastIndexOf("value") + 1, alternate))
                assertEquals("value", result.second.placeholder)
            }
        }
    }

    @Test
    fun `prepare declines a source that changes before validation`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Cases { def call(value) { value } }"
            fixture(source).use {
                var reads = 0
                val provider = RenameProvider(it.compilation) { _ ->
                    reads++
                    if (reads == 1) source else "$source // changed"
                }
                val position = TextIndex(source).positionOf(source.lastIndexOf("value") + 1)
                assertNull(provider.providePrepareRename(it.uri.toString(), position))
                assertEquals(2, reads)
                assertEquals(source, it.documents.get(it.uri))
                assertEquals(41, it.documents.getSnapshot(it.uri)?.version)
            }
        }
    }

    @Test
    fun `prepare propagates compilation cancellation`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Cases { def call(value) { value } }"
            fixture(source).use {
                val compilation = spyk(it.compilation)
                coEvery { compilation.ensureCompiled(it.uri) } throws CancellationException("cancelled")
                val provider = RenameProvider(compilation) { uri -> it.documents.get(uri) }
                assertFailsWith<CancellationException> {
                    provider.providePrepareRename(
                        it.uri.toString(),
                        TextIndex(source).positionOf(source.lastIndexOf("value")),
                    )
                }
            }
        }
    }

    private suspend fun CoroutineScope.fixture(source: String): Fixture {
        val uri = workspace.resolve("Target.groovy").toUri()
        Files.writeString(Path.of(uri), source)
        val documents = DocumentProvider().apply { put(uri, source, 41) }
        val compilation = GroovyCompilationService(
            parentClassLoader = javaClass.classLoader,
            documentProvider = documents,
        )
        compilation.workspaceManager.initializeWorkspace(workspace)
        compilation.workspaceManager.updateWorkspaceModel(workspace, emptyList(), listOf(workspace))
        compilation.compile(uri, source)
        assertNotNull(compilation.getParseResult(uri)?.ast)
        val endpoint = GroovyTextDocumentService(
            this,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        return Fixture(uri, source, documents, compilation, endpoint)
    }

    private data class Fixture(
        val uri: URI,
        val source: String,
        val documents: DocumentProvider,
        val compilation: GroovyCompilationService,
        val endpoint: GroovyTextDocumentService,
    ) : AutoCloseable {
        suspend fun prepare(offset: Int, documentUri: String = uri.toString()) = endpoint.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(documentUri), TextIndex(source).positionOf(offset)),
        ).await()

        suspend fun assertPrepared(offset: Int, name: String) {
            val result = assertNotNull(prepare(offset + name.length / 2))
            assertTrue(result.isSecond, "Prepare must return an explicit range and placeholder")
            val prepared = result.second
            val index = TextIndex(source)
            assertEquals(name, prepared.placeholder)
            assertEquals(Range(index.positionOf(offset), index.positionOf(offset + name.length)), prepared.range)
            assertEquals(source, documents.get(uri))
            assertEquals(source, Files.readString(Path.of(uri)))
            assertEquals(41, documents.getSnapshot(uri)?.version)
        }

        override fun close() = compilation.clearCaches()
    }
}
