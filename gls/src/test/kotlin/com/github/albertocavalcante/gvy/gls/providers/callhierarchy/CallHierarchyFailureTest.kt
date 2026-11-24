// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.callhierarchy

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A failed incoming-calls scan answers like a failed references request: InternalError, a message, no stack. */
class CallHierarchyFailureTest {
    private val uri = URI.create("file:///Calls.groovy")
    private val text = "class Calls {\n    void helper() {}\n    void run() { helper() }\n}\n"
    private val compilation = GroovyCompilationService().also { runBlocking { it.compile(uri, text) } }
    private val params = CallHierarchyIncomingCallsParams(
        CallHierarchyItem(
            "helper",
            SymbolKind.Method,
            uri.toString(),
            Range(Position(1, 4), Position(1, 20)),
            Range(Position(1, 9), Position(1, 15)),
        ),
    )

    private suspend fun withScanThrowing(failure: Throwable, body: suspend () -> Unit) {
        val visitor = assertNotNull(compilation.getAstModel(uri))
        mockkObject(visitor)
        try {
            every { visitor.getAllNodes() } throws failure
            body()
        } finally {
            unmockkObject(visitor)
            compilation.clearCaches()
        }
    }

    @Test
    fun `a failed scan is an internal error response with a message and no stack trace`(): Unit = runBlocking {
        withScanThrowing(IllegalStateException("scan broke")) {
            val error = assertFailsWith<ResponseErrorException> {
                CallHierarchyProvider(compilation).incomingCalls(params)
            }
            assertEquals(ResponseErrorCode.InternalError.value, error.responseError.code)
            val message = error.responseError.message
            assertTrue("finding incoming calls" in message && "scan broke" in message, message)
            assertNull(error.responseError.data, "the client must not receive a Java stack trace")
        }
    }

    @Test
    fun `a cancelled scan stays cancelled`(): Unit = runBlocking {
        withScanThrowing(CancellationException("cancelled")) {
            assertFailsWith<CancellationException> { CallHierarchyProvider(compilation).incomingCalls(params) }
        }
    }
}
