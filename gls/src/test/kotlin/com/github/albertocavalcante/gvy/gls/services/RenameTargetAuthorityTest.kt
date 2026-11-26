// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as AstPosition

class RenameTargetAuthorityTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a stable in-root alias keeps its client URI ranges and version`() {
        val root = Files.createDirectory(directory.resolve("workspace"))
        val original = Files.writeString(root.resolve("Physical.groovy"), LOCAL)
        val alias = Files.createSymbolicLink(root.resolve("Editor.groovy"), original)
        assertTrue(Files.isSameFile(alias, original))
        Fixture(alias, LOCAL, root).use { it.assertLocalAnswers() }
    }

    @Test
    fun `both wire routes preserve the read-only refusal for a file outside the workspace`() {
        val root = Files.createDirectory(directory.resolve("workspace"))
        val external = Files.writeString(directory.resolve("Outside.groovy"), LOCAL)
        assertFalse(external.toRealPath().startsWith(root.toRealPath()))
        Fixture(external, LOCAL, root).use { fixture ->
            val rename = fixture.rename()
            val prepare = fixture.prepare()
            for (response in listOf(rename, prepare)) {
                val outcome = runCatching { response.result() }
                val exception = assertIs<ExecutionException>(outcome.exceptionOrNull(), "wire result: $outcome")
                val refusal = assertIs<ResponseErrorException>(exception.cause)
                assertEquals(ResponseErrorCode.InvalidRequest.value, refusal.responseError.code)
                assertTrue(refusal.responseError.message.contains("read-only"), refusal.message)
            }
        }
        assertEquals(LOCAL, Files.readString(external))
    }

    @Test
    fun `a workspace alias to an owned file outside the synthetic workspace remains read only`() {
        val root = Files.createDirectory(directory.resolve("workspace"))
        val external = Files.writeString(directory.resolve("Comparison.groovy"), LOCAL)
        val alias = Files.createSymbolicLink(root.resolve("Editor.groovy"), external)
        assertFalse(alias.toRealPath().startsWith(root.toRealPath()))
        Fixture(alias, LOCAL, root).use { fixture ->
            assertRefusal(fixture.rename(), ResponseErrorCode.InvalidRequest)
            assertRefusal(fixture.prepare(), ResponseErrorCode.InvalidRequest)
        }
        assertEquals(LOCAL, Files.readString(external))
    }

    @Test
    fun `same text alias retargeting during real native lookup prevents either wire result`() {
        for (prepare in listOf(false, true)) {
            val root = Files.createDirectory(directory.resolve("workspace-$prepare"))
            val first = Files.writeString(root.resolve("First.groovy"), LOCAL)
            val second = Files.writeString(root.resolve("Second.groovy"), LOCAL)
            val alias = Files.createSymbolicLink(root.resolve("Editor.groovy"), first)
            val swaps = AtomicInteger()
            assertTrue(Files.isSameFile(alias, first))
            Fixture(alias, LOCAL, root) {
                assertTrue(Files.isSameFile(alias, first))
                Files.delete(alias)
                Files.createSymbolicLink(alias, second)
                assertTrue(Files.isSameFile(alias, second))
                assertEquals(LOCAL, Files.readString(alias))
                swaps.incrementAndGet()
            }.use { fixture ->
                val answer = if (prepare) fixture.prepare() else fixture.rename()
                assertRefusal(answer, ResponseErrorCode.ContentModified)
                assertEquals(1, swaps.get(), "real native AST lookup must trigger the owned retarget")
                assertEquals(LOCAL, fixture.documents.get(fixture.uri))
            }
            assertEquals(LOCAL, Files.readString(first))
            assertEquals(LOCAL, Files.readString(second))
        }
    }

    @Test
    fun `an unsaved file with multiple missing components under a stable physical ancestor remains editable`() {
        val root = Files.createDirectory(directory.resolve("workspace"))
        val actual = Files.createDirectory(root.resolve("new-sources"))
        val alias = Files.createSymbolicLink(root.resolve("editor-sources"), actual)
        val file = alias.resolve("planned/nested/Draft.groovy")
        assertTrue(Files.isSameFile(alias, actual))
        assertTrue(Files.notExists(alias.resolve("planned")))
        Fixture(file, LOCAL, root).use { it.assertLocalAnswers() }
        assertTrue(Files.notExists(actual.resolve("planned")))
    }

    @Test
    fun `rootless single-file editing retains its exact wire results`() {
        val file = Files.writeString(directory.resolve("Standalone.groovy"), LOCAL)
        Fixture(file, LOCAL, root = null).use { it.assertLocalAnswers() }
    }

    @Test
    fun `a top-level class rename through a stable directory alias targets the sibling in that directory`() {
        val root = Files.createDirectory(directory.resolve("workspace"))
        val actual = Files.createDirectory(root.resolve("actual"))
        val primary = Files.writeString(actual.resolve("Owned.groovy"), TYPE)
        val parent = Files.createSymbolicLink(root.resolve("editor"), actual)
        val source = parent.resolve("Owned.groovy")
        val destination = parent.resolve("Updated.groovy")
        assertTrue(Files.isSameFile(source, primary))
        assertTrue(Files.isSameFile(destination.parent, actual))
        Fixture(source, TYPE, root).use { fixture ->
            val before = fixture.rename("Updated", Position(0, 7)).result().documentChanges
            assertEquals(2, before.size)
            val edit = before.single { it.isLeft }.left
            assertEquals(fixture.uri.toString(), edit.textDocument.uri)
            assertEquals(VERSION, edit.textDocument.version)
            assertEquals(listOf(TextEdit(Range(Position(0, 6), Position(0, 11)), "Updated")), edit.edits)
            val move = assertIs<RenameFile>(before.single { it.isRight }.right)
            assertEquals(fixture.uri, URI.create(move.oldUri))
            assertEquals(destination.toUri(), URI.create(move.newUri))

            assertTrue(Files.notExists(destination))
            assertEquals(TYPE, Files.readString(source))
        }
    }

    private class Fixture(
        path: Path,
        val text: String,
        root: Path?,
        private val afterLookup: () -> Unit = {},
    ) : AutoCloseable {
        val uri = path.toUri()
        val documents = DocumentProvider()
        private val lookupPending = AtomicBoolean(true)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { native ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = native.parse(request)
                        assertEquals(uri, request.uri)
                        assertEquals(text, request.content)
                        assertNotNull(parsed.ast)
                        assertTrue(parsed.outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS)
                        val model = parsed.astModel
                        return parsed.copy(
                            astModel = object : GroovyAstModel by model {
                                override fun getNodeAt(uri: URI, position: AstPosition): ASTNode? {
                                    val node = model.getNodeAt(uri, position)
                                    assertNotNull(node, "native AST must resolve the original symbol")
                                    if (lookupPending.compareAndSet(true, false)) afterLookup()
                                    return node
                                }
                            },
                        )
                    }
                }
            },
        )
        private val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )

        init {
            if (root != null) service.updateWorkspaceFolders(listOf(root))
            compilation.onDocumentOpened(uri)
            documents.put(uri, text, DocumentVersion.of(VERSION))
        }

        fun prepare() = service.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri.toString()), Position(1, 9)),
        )

        fun rename(name: String = "sum", cursor: Position = Position(0, 6)) =
            service.rename(RenameParams(TextDocumentIdentifier(uri.toString()), cursor, name))

        fun assertLocalAnswers() {
            val change = rename().result().documentChanges.single()
            assertTrue(change.isLeft)
            assertEquals(uri.toString(), change.left.textDocument.uri)
            assertEquals(VERSION, change.left.textDocument.version)
            val use = Range(Position(1, 8), Position(1, 13))
            assertEquals(
                listOf(TextEdit(Range(Position(0, 4), Position(0, 9)), "sum"), TextEdit(use, "sum")),
                change.left.edits.sortedBy { it.range.start.line },
            )
            val offer = assertNotNull(prepare().result()).second
            assertEquals("total", offer.placeholder)
            assertEquals(use, offer.range)
            assertEquals(LOCAL, documents.get(uri))
        }

        override fun close() {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private companion object {
        const val LOCAL = "def total = 1\nprintln total\n"
        const val TYPE = "class Owned {}"
        const val VERSION = 41

        fun assertRefusal(response: CompletableFuture<*>, code: ResponseErrorCode) {
            val outcome = runCatching { response.result() }
            val exception = assertIs<ExecutionException>(outcome.exceptionOrNull(), "wire result: $outcome")
            val refusal = assertIs<ResponseErrorException>(exception.cause)
            assertEquals(code.value, refusal.responseError.code, refusal.message)
        }

        fun <T> CompletableFuture<T>.result(): T = get(10, TimeUnit.SECONDS)
    }
}
