// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import com.github.albertocavalcante.nativeapi.ParseMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CrossFileReferenceBindingTest {
    @Test
    fun `references to a source method exclude an identically indexed JDK method`(@TempDir root: Path): Unit =
        runBlocking {
            val method = "drainPermits"
            val source = "package java.util.concurrent; class Semaphore { " +
                "int drainPermits() { 0 }; def workspaceCaller() { drainPermits() } }"
            val target = Files.writeString(root.resolve("Semaphore.groovy"), source).toUri()
            val callerText =
                "class Caller { def binaryCaller(java.util.concurrent.Semaphore permits) { permits.drainPermits() } }"
            val caller = Files.writeString(root.resolve("Caller.groovy"), callerText).toUri()
            val documents = DocumentProvider()
            val compilation = GroovyCompilationService(documentProvider = documents)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
            val service = GroovyTextDocumentService(scope, compilation, options)
            try {
                assertNotNull(compilation.ensureCompiled(target))
                val at = Position(0, source.indexOf(method))
                val params = ReferenceParams(TextDocumentIdentifier(target.toString()), at, ReferenceContext(false))
                val call = source.lastIndexOf(method)
                val range = Range(Position(0, call), Position(0, call + method.length))
                val expected = listOf(Location(target.toString(), range))
                assertEquals(expected, service.references(params).get(15, TimeUnit.SECONDS))

                assertNotNull(compilation.ensureCompiled(caller))
                val ready = compilation.referenceReadiness(target).query { qualified ->
                    val parses = setOf(target, caller).associateWith { uri ->
                        assertEquals(ParseMode.MINIMAL, qualified.modeFor(uri))
                        val acquired = compilation.acquireSnapshot(uri, mode = ParseMode.MINIMAL)
                        val held = assertIs<SnapshotAcquisition.Ready>(acquired)
                        assertTrue(held.isCurrent() && qualified.matches(uri, held.snapshot))
                        val parsed = held.snapshot.parseResult
                        assertTrue(parsed.outcome.achievedPhase >= Phases.CANONICALIZATION)
                        parsed
                    }
                    val owner = assertNotNull(parses.getValue(target).ast).classes.single { it.name == OWNER }
                    assertTrue(owner.isPrimaryClassNode)
                    val sourceMethod = owner.getDeclaredMethods(method).single()
                    val sourceId = SemanticDocumentBuilder.createMethodSymbolId(owner, sourceMethod).id
                    val binaryParse = parses.getValue(caller)
                    val expression = binaryParse.astModel.getNodes(caller).filterIsInstance<MethodCallExpression>()
                        .single { it.methodAsString == method }
                    val parameter = assertNotNull(binaryParse.ast).classes.single { it.name == "Caller" }
                        .getDeclaredMethods("binaryCaller").single().parameters.single()
                    assertSame(parameter, assertIs<VariableExpression>(expression.objectExpression).accessedVariable)
                    val binary = parameter.type.redirect()
                    assertEquals(OWNER, binary.name)
                    assertFalse(binary.isPrimaryClassNode)
                    assertTrue(binary.isResolved)
                    assertNull(binary.module)
                    val binaryMethod = binary.getDeclaredMethods(method).single()
                    assertEquals(sourceId, SemanticDocumentBuilder.createMethodSymbolId(binary, binaryMethod).id)
                    val indexed = assertNotNull(qualified.index.getDocumentSnapshot(caller))
                    assertEquals(callerText, indexed.sourceText)
                    assertTrue(indexed.document.occurrences.any { it.symbol == sourceId })
                }
                assertIs<SnapshotQuery.Answer<Unit>>(ready)

                try {
                    assertEquals(expected, service.references(params).get(15, TimeUnit.SECONDS))
                } catch (failure: ExecutionException) {
                    val error = assertIs<ResponseErrorException>(failure.cause).responseError
                    assertEquals(ResponseErrorCode.RequestFailed.value, error.code)
                }
            } finally {
                scope.cancel()
                compilation.shutdown()
            }
        }

    private companion object {
        const val OWNER = "java.util.concurrent.Semaphore"
    }
}
