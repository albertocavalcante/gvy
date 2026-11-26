// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IncomingPublicationBindingTest {
    @Test
    fun `a preexisting same-text replacement cannot borrow its predecessor's producer`(): Unit = runBlocking {
        val origin = URI.create("file:///Target.groovy")
        val caller = URI.create("file:///Caller.groovy")
        val originText = "class Target { def work() {} }"
        val callerText = "class Caller { def run(Target target) { target.work() } }"
        val documents = DocumentProvider().apply {
            put(origin, originText)
            put(caller, callerText)
        }
        val inventory = documents.captureDocuments()
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
        val service = GroovyTextDocumentService(scope, compilation, options)
        try {
            compilation.compile(origin, originText)
            compilation.compile(caller, callerText)
            val position = Position(0, originText.indexOf("work"))
            val params = CallHierarchyPrepareParams(TextDocumentIdentifier(origin.toString()), position)
            val item = service.prepareCallHierarchy(params).get(10, TimeUnit.SECONDS).single()
            fun incoming() = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
            val healthy = incoming().get(10, TimeUnit.SECONDS)
            assertEquals(listOf(caller.toString() to "run"), healthy.map { it.from.uri to it.from.name })

            val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
            val qualified = compilation.referenceReadiness(origin).query { refs ->
                assertNotNull(refs.index.getDocumentSnapshot(caller)).document
            }
            val original = assertIs<SnapshotQuery.Answer<*>>(qualified).value
            val stored = assertNotNull(index.getDocument(caller))
            assertSame(original, stored)
            val target = assertNotNull(index.getDocument(origin)).symbols.single { it.name == "work" }.symbol
            val remaining = stored.occurrences.filter { it.symbol != target }
            assertTrue(remaining.size < stored.occurrences.size)
            index.updateDocument(caller, stored.copy(occurrences = remaining), callerText)

            val replaced = index.captureReferences()
            assertEquals(callerText, assertNotNull(replaced.getDocumentSnapshot(caller)).sourceText)
            assertFalse(caller in replaced.findReferenceDocuments(target))
            assertSame(inventory, documents.captureDocuments())
            assertTrue(replaced.isCurrent())

            val failure = assertThrows<ExecutionException> { incoming().get(10, TimeUnit.SECONDS) }
            val response = assertIs<ResponseErrorException>(failure.cause).responseError
            assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
