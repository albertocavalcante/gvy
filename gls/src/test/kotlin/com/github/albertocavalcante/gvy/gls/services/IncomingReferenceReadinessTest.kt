// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertIs

class IncomingReferenceReadinessTest {
    @Test
    fun `a listed missing caller cannot silently produce a complete incoming answer`(@TempDir root: Path) {
        val target = root.resolve("Target.groovy")
        val source = "class Target { static def target() {} }"
        val caller = root.resolve("Caller.groovy")
        Files.writeString(target, source)
        Files.writeString(caller, "class Caller { def call() { Target.target() } }")

        val documents = DocumentProvider().apply { put(target.toUri(), source) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            assertContains(compilation.workspaceManager.current().sources, caller)
            compilation.onDocumentOpened(target.toUri())
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
            val service = GroovyTextDocumentService(scope, compilation, options)
            val item = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(
                    TextDocumentIdentifier(target.toUri().toString()),
                    Position(0, source.indexOf("target")),
                ),
            ).get(10, TimeUnit.SECONDS).single()
            Files.delete(caller)

            val request = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
            val failure = assertThrows<ExecutionException> { request.get(10, TimeUnit.SECONDS) }
            val code = assertIs<ResponseErrorException>(failure.cause).responseError.code
            assertContains(setOf(ResponseErrorCode.ContentModified.value, ResponseErrorCode.RequestFailed.value), code)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
