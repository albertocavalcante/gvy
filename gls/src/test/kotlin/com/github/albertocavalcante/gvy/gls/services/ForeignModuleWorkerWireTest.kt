// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Inject the documented first-module fallback shape after a real native compile of both exact sources. */
class ForeignModuleWorkerWireTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a foreign worker module cannot publish as the primary`(inject: Boolean): Unit = runBlocking {
        supervisorScope {
            val target = root.resolve("Primary.groovy").toUri()
            val peer = Files.writeString(root.resolve("Peer.groovy"), PEER)
            val documents = DocumentProvider()
            val reached = AtomicBoolean()
            val compiler = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { original ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = original.parse(request)
                        var transferred = false
                        try {
                            assertEquals(target, request.uri)
                            assertEquals(PRIMARY, request.content)
                            assertTrue(peer in request.workspaceSources)
                            val output = selectModule(parsed, target, peer, inject)
                            reached.set(true)
                            transferred = true
                            return output
                        } finally {
                            if (!transferred) parsed.compilationUnit.classLoader.close()
                        }
                    }
                }
            })
            try {
                val endpoint = GroovyWorkspaceService(compiler, compiler.symbolIndexingService, this)
                compiler.updateWorkspaceModel(root, emptyList(), listOf(root))
                compiler.onDocumentOpened(target)
                documents.put(target, PRIMARY, DocumentVersion.of(1))
                compiler.compile(target, PRIMARY)
                assertTrue(reached.get(), "The selected worker must supply the stipulated native result")
                try {
                    val rows = endpoint.symbol(WorkspaceSymbolParams("FixtureMarker")).await().left
                    val local = rows.filter { it.location.uri == target.toString() }.map { it.name }
                    assertEquals(if (inject) emptyList() else listOf("localFixtureMarker"), local)
                } catch (error: ResponseErrorException) {
                    if (!inject) throw error
                    assertEquals(ResponseErrorCode.RequestFailed.value, error.responseError.code)
                }
            } finally {
                coroutineContext.cancelChildren()
                compiler.shutdown()
            }
        }
    }

    private fun selectModule(parsed: ParseResult, target: URI, peer: Path, inject: Boolean): ParseResult {
        val modules = parsed.compilationUnit.ast.modules
        val main = assertNotNull(parsed.ast)
        assertSame(parsed.sourceUnit.ast, main)
        assertTrue(assertNotNull(CompiledDocumentInput.of(main)).matchesText(PRIMARY))
        val sibling = modules.single { CompiledDocumentInput.of(it)?.uri == peer.toUri() }
        val read = assertNotNull(CompiledDocumentInput.of(sibling))
        assertTrue(read.matchesCurrentText(null) {} && read.matchesText(PEER))
        val foreign = sibling.classes.single().getDeclaredMethods("foreignFixtureMarker").single()
        assertTrue(main.classes.single().getDeclaredMethods(foreign.name).isEmpty())
        if (!inject) return parsed

        // Reproduce extractAst's fallback + visitModule binding. The compiler did read the physical peer.
        assertTrue(modules.remove(main))
        assertSame(sibling, modules.first())
        assertTrue(modules.none { it.context === parsed.sourceUnit })
        val model = RecursiveAstVisitor(NodeRelationshipTracker()).apply { visitModule(sibling, target) }
        assertEquals(target, model.getUri(foreign))
        val symbols = SymbolTable().apply { buildFromVisitor(model) }
        return parsed.copy(ast = sibling, astModel = model, symbolTable = symbols)
    }

    private companion object {
        const val PRIMARY = "// foreignFixtureMarker appears only in this comment, never as a primary declaration\n" +
            "class Primary { void localFixtureMarker() {} }"
        const val PEER = "class Peer { void foreignFixtureMarker() {} }"
    }
}
