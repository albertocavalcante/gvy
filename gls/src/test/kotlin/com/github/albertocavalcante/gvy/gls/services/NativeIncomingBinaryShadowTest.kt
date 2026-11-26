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
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
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

class NativeIncomingBinaryShadowTest {
    @Test
    fun `incoming calls to a workspace declaration exclude uses of an identically named JDK method`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val targetText = "package java.util.concurrent; class Semaphore { " +
            "int drainPermits() { 0 }; def workspaceCaller() { drainPermits() } }"
        val target = Files.writeString(root.resolve("Semaphore.groovy"), targetText).toUri()
        val caller = Files.writeString(
            root.resolve("Caller.groovy"),
            "class Caller { def binaryCaller(java.util.concurrent.Semaphore permits) { permits.drainPermits() } }",
        ).toUri()
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
        val service = GroovyTextDocumentService(scope, compilation, options)
        try {
            assertNotNull(compilation.ensureCompiled(target))
            val position = Position(0, targetText.indexOf("drainPermits"))
            val item = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(target.toString()), position),
            ).get(10, TimeUnit.SECONDS).single()
            fun incoming() = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                .get(10, TimeUnit.SECONDS).map { it.from.uri to it.from.name }
            val expected = listOf(target.toString() to "workspaceCaller")
            assertEquals(expected, incoming())

            assertNotNull(compilation.ensureCompiled(caller))
            assertClosedBindings(compilation, target, caller)
            try {
                assertEquals(expected, incoming(), "binaryCaller invokes the JDK declaration")
            } catch (failure: ExecutionException) {
                val response = assertIs<ResponseErrorException>(failure.cause).responseError
                assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
            }
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private suspend fun assertClosedBindings(compilation: GroovyCompilationService, target: URI, caller: URI) {
        val files = setOf(target, caller)
        for (origin in files) {
            // Each file has to qualify as a closed, non-origin peer in one of the two queries.
            val checked = compilation.referenceReadiness(origin).query { references ->
                assertEquals(files, references.index.visibleDocuments)
                val bindings = files.associateWith { uri ->
                    assertEquals(ParseMode.MINIMAL, references.modeFor(uri))
                    val acquired = compilation.acquireSnapshot(uri, mode = ParseMode.MINIMAL)
                    val held = assertIs<SnapshotAcquisition.Ready>(acquired)
                    assertTrue(held.isCurrent())
                    assertTrue(references.matches(uri, held.snapshot))
                    held.snapshot.parseResult
                }
                val sourceOwner = assertNotNull(bindings.getValue(target).ast).classes.single { it.name == SEMAPHORE }
                assertTrue(sourceOwner.isPrimaryClassNode)
                val sourceMethod = sourceOwner.getDeclaredMethods("drainPermits").single()
                val targetId = assertNotNull(references.index.getDocumentSnapshot(target)).document.symbols
                    .single { it.name == "drainPermits" }.symbol
                assertEquals(targetId, SemanticDocumentBuilder.createMethodSymbolId(sourceOwner, sourceMethod).id)

                val callParse = bindings.getValue(caller)
                val call = callParse.astModel.getNodes(caller).filterIsInstance<MethodCallExpression>()
                    .single { it.methodAsString == "drainPermits" }
                val receiver = assertIs<VariableExpression>(call.objectExpression)
                val parameter = assertNotNull(callParse.ast).classes.single { it.name == "Caller" }
                    .getDeclaredMethods("binaryCaller").single().parameters.single()
                assertSame(parameter, receiver.accessedVariable)
                val binaryOwner = parameter.type.redirect()
                assertEquals(SEMAPHORE, binaryOwner.name)
                assertFalse(binaryOwner.isPrimaryClassNode)
                assertTrue(binaryOwner.isResolved)
                assertNull(binaryOwner.module)
                val binaryMethod = binaryOwner.getDeclaredMethods("drainPermits").single()
                assertEquals(targetId, SemanticDocumentBuilder.createMethodSymbolId(binaryOwner, binaryMethod).id)
            }
            assertIs<SnapshotQuery.Answer<Unit>>(checked)
        }
    }

    private companion object {
        const val SEMAPHORE = "java.util.concurrent.Semaphore"
    }
}
