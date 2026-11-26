// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue

class CompletionGdkSnapshotControlTest {
    @Test
    fun `a compiled String receiver offers both center extension overloads`(): Unit = runBlocking {
        val uri = URI.create("file:///GdkControl.groovy")
        val text = "'hello'.ce"
        val documents = DocumentProvider().apply { put(uri, text) }
        val selectedString = AtomicBoolean()
        val compilation = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also { result ->
                    val literal = result.astModel.getNodes(uri).filterIsInstance<ConstantExpression>()
                        .singleOrNull { it.value == "hello" }
                    selectedString.set(
                        request.compilePhase == Phases.CANONICALIZATION &&
                            literal?.type?.redirect()?.let {
                                it.isResolved && !it.isPrimaryClassNode &&
                                    it.name == "java.lang.String"
                            } ==
                            true,
                    )
                }
            }
        })
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = GroovyTextDocumentService(scope, compilation)
            val answer = service.completion(
                CompletionParams(TextDocumentIdentifier(uri.toString()), Position(0, text.length)),
            ).await()
            assertTrue(selectedString.get(), "literal in the actual dummy parse must bind java.lang.String")
            val items = if (answer.isLeft) answer.left else answer.right.items
            val overloads = items.filter { it.label == "center" }.map { it.detail }
            assertTrue(
                overloads.any {
                    it?.contains("Number") == true && !it.contains(",")
                },
                "missing one-argument center: $overloads",
            )
            assertTrue(
                overloads.any {
                    it?.contains("Number") == true && it.contains(",")
                },
                "missing padded center: $overloads",
            )
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
