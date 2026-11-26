// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IncomingCompanionBindingTest {
    @Test
    fun `a high phase index built with missing-class stubs cannot certify incoming callers`(): Unit = runBlocking {
        val uri = URI.create("file:///Example.groovy")
        val source = "class Example { Missing field; def target() {} }"
        val documents = DocumentProvider().apply { put(uri, source) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.compile(uri, source)
            val outcome = assertNotNull(compilation.getParseResult(uri)).outcome
            assertTrue(outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS)
            assertTrue(outcome.stubbedClasses.isNotEmpty(), "the native compiler must have used missing-class recovery")
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
            val service = GroovyTextDocumentService(scope, compilation, options)
            val position = Position(0, source.indexOf("target"))
            val target = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(uri.toString()), position),
            ).get(10, TimeUnit.SECONDS).single()
            val result = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(target))
            val failure = assertThrows<ExecutionException> { result.get(10, TimeUnit.SECONDS) }
            val code = assertIs<ResponseErrorException>(failure.cause).responseError.code
            assertContains(setOf(ResponseErrorCode.ContentModified.value, ResponseErrorCode.RequestFailed.value), code)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    @Test
    fun `incoming callers cannot disappear when only the target parameter changes`(): Unit = runBlocking {
        val peer = URI.create("file:///Peer.groovy")
        val subject = URI.create("file:///Subject.groovy")
        val original = "class Peer { def target(String value) {} }"
        val changed = "class Peer { def target(Object value) {} }"
        val caller = "class Subject { def call(Peer p) { p.target('x') } }"
        val documents = DocumentProvider().apply {
            put(peer, original, DocumentVersion.of(1))
            put(subject, caller, DocumentVersion.of(1))
        }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.compile(peer, original)
            compilation.compile(subject, caller)
            val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
            fun targetId() = assertNotNull(index.getDocument(peer)).symbols.single { it.name == "target" }.symbol
            val previous = targetId()
            assertContains(index.findReferenceDocuments(previous).keys, subject)

            documents.update(peer, changed, DocumentVersion.of(2))
            compilation.compile(peer, changed)
            assertNotEquals(previous, targetId())
            assertContains(index.findReferenceDocuments(previous).keys, subject)
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
            val service = GroovyTextDocumentService(scope, compilation, options)
            val target = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(
                    TextDocumentIdentifier(peer.toString()),
                    Position(0, changed.indexOf("target")),
                ),
            ).get(10, TimeUnit.SECONDS).single()
            val result = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(target))
            try {
                val incoming = result.get(10, TimeUnit.SECONDS)
                assertEquals(listOf(subject.toString() to "call"), incoming.map { it.from.uri to it.from.name })
            } catch (failure: ExecutionException) {
                val code = assertIs<ResponseErrorException>(failure.cause).responseError.code
                val unavailable = setOf(ResponseErrorCode.ContentModified.value, ResponseErrorCode.RequestFailed.value)
                assertContains(unavailable, code)
            }
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
