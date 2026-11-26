// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class CallHierarchyDeclarationTest {
    @Test
    fun `constructor uses share the positioned local declaration`() {
        val source = "class Local {}\nclass Caller { def run() { new Local(); new Local() } }\n"
        val uri = URI.create("file:///Local.groovy")
        val documents = DocumentProvider().apply { put(uri, source, DocumentVersion.of(1)) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val service = GroovyTextDocumentService(scope, compilation)
            val id = TextDocumentIdentifier(uri.toString())
            val caller = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(id, Position(1, 20)),
            ).get(10, TimeUnit.SECONDS).single()
            val calls = service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(caller))
                .get(10, TimeUnit.SECONDS)
            assertEquals(1, calls.size, "both calls must group by the class declaration")
            val call = calls.single()
            assertEquals("Local", call.to.name)
            assertEquals(uri.toString(), call.to.uri)
            assertEquals(Range(Position(0, 6), Position(0, 11)), call.to.selectionRange)
            assertEquals(Range(Position(0, 0), Position(0, 14)), call.to.range)
            assertEquals(
                listOf(Range(Position(1, 27), Position(1, 38)), Range(Position(1, 40), Position(1, 51))),
                call.fromRanges,
            )
            val preparedUse = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(id, Position(1, 32)),
            ).get(10, TimeUnit.SECONDS).single()
            assertEquals(call.to.range, preparedUse.range)
            assertEquals(call.to.selectionRange, preparedUse.selectionRange)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
