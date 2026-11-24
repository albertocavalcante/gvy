// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.crossfile

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.callhierarchy.CallHierarchyProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.source.DocumentVersion
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import kotlin.test.assertTrue

/**
 * A failed reference scan must reach the client as a failed request, not as a shorter list it would
 * show as complete. Call hierarchy is built on the same scan and must not report a subset of callers either.
 */
class ReferenceScanFailureTest {
    private val uri = URI.create("file:///Scan.groovy")
    private val text = "class Scan {\n    void helper() {}\n    void run() { helper() }\n}\n"
    private val callOfHelper = Position(2, 19)
    private val documents = DocumentProvider().apply { put(uri, text, DocumentVersion.of(1)) }
    private val compilation = GroovyCompilationService(documentProvider = documents)

    @Test
    fun `references report an internal error when the scan fails`(): Unit = runBlocking {
        withFailingScan {
            val error = assertFailsWith<ResponseErrorException> {
                handler().getReferences(uri.toString(), callOfHelper, includeDeclaration = true)
            }
            assertEquals(ResponseErrorCode.InternalError.value, error.responseError.code)
            assertTrue("scan broke" in error.responseError.message, error.responseError.message)
        }
    }

    @Test
    fun `incoming calls do not report a subset of callers when the scan fails`(): Unit = runBlocking {
        withFailingScan {
            val item = CallHierarchyItem(
                "helper",
                SymbolKind.Method,
                uri.toString(),
                Range(Position(1, 4), Position(1, 20)),
                Range(Position(1, 9), Position(1, 15)),
            )
            val error = assertFailsWith<ResponseErrorException> {
                CallHierarchyProvider(compilation).incomingCalls(CallHierarchyIncomingCallsParams(item))
            }
            assertEquals(ResponseErrorCode.InternalError.value, error.responseError.code)
        }
    }

    @Test
    fun `references still answer when nothing fails`(): Unit = runBlocking {
        compilation.compile(uri, text)
        val references = handler().getReferences(uri.toString(), callOfHelper, includeDeclaration = true)
        assertEquals(2, references.size, "control: the declaration and the call")
    }

    private suspend fun withFailingScan(body: suspend () -> Unit) {
        compilation.compile(uri, text)
        val visitor = assertNotNull(compilation.getAstModel(uri))
        mockkObject(visitor)
        try {
            every { visitor.getAllNodes() } throws IllegalStateException("scan broke")
            body()
        } finally {
            unmockkObject(visitor)
            compilation.clearCaches()
        }
    }

    private fun handler() = CrossFileOperationsHandler(
        compilation,
        documents,
        mockk<SourceNavigator>(relaxed = true),
        { false },
        {},
    )
}
