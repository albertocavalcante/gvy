// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallHierarchyQueuedFollowupTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `queued hierarchy followup retains its original companion fence`(incoming: Boolean) {
        val uri = URI.create("file:///QueuedFollowup.groovy")
        val companion = URI.create("file:///Companion.groovy")
        val documents = DocumentProvider().apply {
            put(uri, "def target() {}\ntarget()\n", DocumentVersion.of(1))
            put(companion, "class Companion {}\n", DocumentVersion.of(1))
        }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val preparing = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    block.run()
                }
            }
        }
        val queued = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val prepared = GroovyTextDocumentService(preparing, compilation).prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(uri.toString()), Position(0, 5)),
            ).get(10, TimeUnit.SECONDS).single()
            val service = GroovyTextDocumentService(queued, compilation)
            val answer = if (incoming) {
                service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(prepared))
            } else {
                service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(prepared))
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            documents.update(companion, "class ChangedCompanion {}\n", DocumentVersion.of(2))
            release.countDown()
            val error = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
            assertEquals(
                ResponseErrorCode.ContentModified.value,
                (error.cause as ResponseErrorException).responseError.code,
            )
        } finally {
            release.countDown()
            queued.cancel()
            preparing.cancel()
            compilation.shutdown()
        }
    }
}
