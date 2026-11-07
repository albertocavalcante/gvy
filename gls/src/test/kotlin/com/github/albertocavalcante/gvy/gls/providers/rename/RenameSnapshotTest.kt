package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Execution(ExecutionMode.SAME_THREAD)
class RenameSnapshotTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `public rename edits carry the captured open document version`(): Unit = runBlocking {
        supervisorScope {
            fixture().use { fixture ->
                val result = fixture.endpoint(this).rename(fixture.params()).await()
                val edits = result.documentChanges.filter { it.isLeft }.map { it.left }

                assertEquals(listOf(41), edits.map { it.textDocument.version })
                assertEquals(2, edits.single().edits.size)
            }
        }
    }

    @Test
    fun `public rename rejects a buffer newer than the compiled ranges`(): Unit = runBlocking {
        supervisorScope {
            fixture().use { fixture ->
                val stale = assertNotNull(fixture.compilation.ensureCompiled(fixture.uri))
                coEvery { fixture.compilation.ensureCompiled(fixture.uri) } returns stale
                fixture.documents.put(fixture.uri, "def total = 1\n// total must stay\nprintln total\n", 42)

                val error = assertFailsWith<ResponseErrorException> {
                    fixture.endpoint(this).rename(fixture.params()).await()
                }

                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            }
        }
    }

    @Test
    fun `public rename accepts a refreshed current buffer and preserves its comment`(): Unit = runBlocking {
        supervisorScope {
            fixture().use { fixture ->
                fixture.documents.put(fixture.uri, "def total = 1\n// total must stay\nprintln total\n", 42)

                val result = fixture.endpoint(this).rename(fixture.params()).await()
                val edits = result.documentChanges.single().left

                assertEquals(42, edits.textDocument.version)
                assertEquals(listOf(0, 2), edits.edits.map { it.range.start.line }.sorted())
                assertTrue(edits.edits.all { it.newText == "sum" })
            }
        }
    }

    @Test
    fun `public rename rejects a version change during acquisition`(): Unit = runBlocking {
        supervisorScope {
            fixture().use { fixture ->
                coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                    callOriginal().also {
                        fixture.documents.put(fixture.uri, fixture.source, 42)
                    }
                }

                val error = assertFailsWith<ResponseErrorException> {
                    fixture.endpoint(this).rename(fixture.params()).await()
                }

                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            }
        }
    }

    @Test
    fun `unreadable participating source rejects the rename as modified content`(): Unit = runBlocking {
        fixture().use { fixture ->
            val provider = RenameProvider(fixture.compilation) { throw IOException("source unavailable") }

            val error = assertFailsWith<ResponseErrorException> {
                provider.provideRename(fixture.uri.toString(), Position(0, 6), "sum")
            }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    @Test
    fun `cancellation while acquiring source is propagated`(): Unit = runBlocking {
        fixture().use { fixture ->
            val provider = RenameProvider(fixture.compilation) { throw CancellationException("cancelled") }

            assertFailsWith<CancellationException> {
                provider.provideRename(fixture.uri.toString(), Position(0, 6), "sum")
            }
        }
    }

    @Test
    fun `public rename propagates compilation cancellation`(): Unit = runBlocking {
        supervisorScope {
            fixture().use { fixture ->
                coEvery { fixture.compilation.ensureCompiled(fixture.uri) } throws CancellationException("cancelled")

                assertFailsWith<CancellationException> {
                    fixture.endpoint(this).rename(fixture.params()).await()
                }
            }
        }
    }

    @Test
    fun `a mismatched visitor cannot turn a variable rename into a file rename`(): Unit = runBlocking {
        supervisorScope {
            fixture("total.groovy").use { fixture ->
                fixture.compilation.compile(fixture.uri, "class total {}")
                val alternate = assertNotNull(fixture.compilation.getAstModel(fixture.uri))
                fixture.compilation.compile(fixture.uri, fixture.source)
                val original = assertNotNull(fixture.compilation.getAstModel(fixture.uri))
                every { fixture.compilation.getAstModel(fixture.uri) } returnsMany listOf(alternate, original)

                val result = fixture.endpoint(this).rename(fixture.params()).await()

                assertTrue(result.documentChanges.all { it.isLeft }, "A local rename must not rename its source file")
                assertEquals(2, result.documentChanges.single().left.edits.size)
            }
        }
    }

    @Test
    fun `reference failure after a declaration is emitted rejects every edit`(): Unit = runBlocking {
        assertScanFailure(cancelled = false)
    }

    @Test
    fun `reference cancellation after a declaration is emitted propagates`(): Unit = runBlocking {
        assertScanFailure(cancelled = true)
    }

    private suspend fun assertScanFailure(cancelled: Boolean) {
        fixture().use { fixture ->
            var instrumented: GroovyAstModel? = null
            var declarationLookups = 0
            var failedLookups = 0
            var scanning = false
            try {
                coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                    callOriginal().also {
                        val visitor = assertNotNull(fixture.compilation.getAstModel(fixture.uri))
                        val nodes = visitor.getAllNodes()
                        val variables = nodes.filterIsInstance<VariableExpression>()
                            .filter { variable -> variable.name == "total" }
                        val declaration = variables.single { variable -> variable.lineNumber == 1 }
                        val usage = variables.single { variable -> variable.lineNumber == 2 }
                        val ordered = listOf(declaration) + nodes.filter { node ->
                            node !== declaration && node !== usage
                        } + usage
                        instrumented = visitor
                        mockkObject(visitor)
                        every { visitor.getAllNodes() } answers {
                            scanning = true
                            ordered
                        }
                        every { visitor.getUri(declaration) } answers {
                            if (scanning) declarationLookups++
                            fixture.uri
                        }
                        every { visitor.getUri(usage) } answers {
                            assertTrue(declarationLookups > 0, "The scan must visit the declaration before failing")
                            failedLookups++
                            if (cancelled) throw CancellationException("reference scan cancelled")
                            error("reference lookup failed")
                        }
                    }
                }
                val provider = RenameProvider(fixture.compilation) { fixture.documents.get(it) }
                if (cancelled) {
                    assertFailsWith<CancellationException> {
                        provider.provideRename(fixture.uri.toString(), Position(0, 6), "sum")
                    }
                } else {
                    val error = assertFailsWith<ResponseErrorException> {
                        provider.provideRename(fixture.uri.toString(), Position(0, 6), "sum")
                    }
                    assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
                }
                assertTrue(declarationLookups > 0, "The scan must visit its declaration reference")
                assertTrue(failedLookups > 0, "The real reference scan must reach the failing usage")
            } finally {
                instrumented?.let { unmockkObject(it) }
            }
        }
    }

    @Test
    fun `unversioned document callers retain unversioned edits`(): Unit = runBlocking {
        supervisorScope {
            fixture().use { fixture ->
                fixture.documents.put(fixture.uri, fixture.source)

                val result = fixture.endpoint(this).rename(fixture.params()).await()
                val edits = result.documentChanges.filter { it.isLeft }.map { it.left }

                assertEquals(listOf(null), edits.map { it.textDocument.version })
                assertEquals(2, edits.single().edits.size)
            }
        }
    }

    @Test
    fun `equivalent URI spellings retain captured rename source identity`(): Unit = runBlocking {
        supervisorScope {
            fixture().use { fixture ->
                val endpoint = fixture.endpoint(this)
                fixture.compilation.ensureCompiled(fixture.uri)
                val captured = assertNotNull(fixture.compilation.getCompiledSnapshot(fixture.uri))
                every { fixture.compilation.getCompiledSnapshot(fixture.uri) } returns captured
                val params = fixture.params().apply {
                    textDocument.uri = fixture.uri.toString().replace("file:///", "file:/")
                }
                assertEquals(fixture.uri, URI.create(params.textDocument.uri))

                val result = endpoint.rename(params).await()

                assertEquals(41, result.documentChanges.single().left.textDocument.version)
                assertEquals(2, result.documentChanges.single().left.edits.size)
            }
        }
    }

    @Test
    fun `evicting a captured parse does not invalidate an unchanged rename input`(): Unit = runBlocking {
        supervisorScope {
            fixture().use { fixture ->
                var instrumented: GroovyAstModel? = null
                var evicted = false
                try {
                    coEvery { fixture.compilation.ensureCompiled(fixture.uri) } coAnswers {
                        callOriginal().also {
                            val visitor = assertNotNull(fixture.compilation.getAstModel(fixture.uri))
                            instrumented = visitor
                            mockkObject(visitor)
                            every { visitor.getAllNodes() } answers {
                                if (!evicted) {
                                    evicted = true
                                    fixture.compilation.clearCaches()
                                }
                                callOriginal()
                            }
                        }
                    }

                    val result = fixture.endpoint(this).rename(fixture.params()).await()

                    assertTrue(evicted, "The actual reference scan must cross the cache invalidation")
                    assertEquals(41, result.documentChanges.single().left.textDocument.version)
                    assertEquals(2, result.documentChanges.single().left.edits.size)
                } finally {
                    instrumented?.let { unmockkObject(it) }
                }
            }
        }
    }

    @Test
    fun `a parameter rename leaves equally typed parameters in other methods alone`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Example {\n    String one(String value) { value }\n" +
                "    String two(String value) { value }\n}\n"
            fixture(source = source).use { fixture ->
                val result = fixture.endpoint(this).rename(
                    RenameParams(TextDocumentIdentifier(fixture.uri.toString()), Position(1, 24), "result"),
                ).await()

                val edits = result.documentChanges.single().left.edits
                assertEquals(listOf(1, 1), edits.map { it.range.start.line }.sorted())
                assertTrue(edits.all { it.newText == "result" })
            }
        }
    }

    @Test
    fun `a parameter named like its type renames only its declaration and usage`(): Unit = runBlocking {
        supervisorScope {
            val source = "class Example {\n    Object one(String String) { String }\n}\n"
            fixture(source = source).use { fixture ->
                val index = TextIndex(source)
                val declaration = source.indexOf("String String") + "String ".length
                val usage = source.indexOf("{ String }") + "{ ".length
                val result = fixture.endpoint(this).rename(
                    RenameParams(
                        TextDocumentIdentifier(fixture.uri.toString()),
                        index.positionOf(declaration),
                        "renamed",
                    ),
                ).await()

                val edits = result.documentChanges.single().left.edits.sortedBy { it.range.start.character }
                assertEquals(
                    listOf(declaration, usage).map { offset ->
                        Range(index.positionOf(offset), index.positionOf(offset + "String".length))
                    },
                    edits.map { it.range },
                )
                assertEquals(listOf("renamed", "renamed"), edits.map { it.newText })
            }
        }
    }

    private suspend fun fixture(
        filename: String = "Rename.groovy",
        source: String = "def total = 1\nprintln total\n",
    ): Fixture {
        val uri = workspace.resolve(filename).toUri()
        Files.writeString(Path.of(uri), source)
        val documents = DocumentProvider().apply { put(uri, source, 41) }
        val compilation = spyk(GroovyCompilationService(documentProvider = documents))
        compilation.workspaceManager.initializeWorkspace(workspace)
        val result = compilation.compile(uri, source)
        assertTrue(result.isSuccess, result.diagnostics.toString())
        return Fixture(uri, source, documents, compilation)
    }

    private data class Fixture(
        val uri: URI,
        val source: String,
        val documents: DocumentProvider,
        val compilation: GroovyCompilationService,
    ) : AutoCloseable {
        fun params() = RenameParams(TextDocumentIdentifier(uri.toString()), Position(0, 6), "sum")

        fun endpoint(scope: CoroutineScope) = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )

        override fun close() = compilation.clearCaches()
    }
}
