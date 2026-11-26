// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CrossFilePartialNavigationTest {
    @Test
    fun `recovered native origin keeps local navigation and refuses a complete reference scan`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val target = root.resolve("Target.groovy").toUri()
        val caller = root.resolve("Caller.groovy").toUri()
        val support = root.resolve("Support.groovy")
        val supportText = "class Support {}"
        Files.writeString(support, supportText)
        val text = "class Target { def work() { def count = 1; println count } }"
        val callerText = "class Caller { def run(Target target) { target.work() } }"
        val documents = DocumentProvider().apply {
            put(target, text)
            put(caller, callerText)
        }
        val recover = AtomicBoolean()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { native ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        if (!recover.get() || request.uri != target ||
                            request.compilePhase != Phases.CANONICALIZATION
                        ) {
                            return native.parse(request)
                        }
                        assertEquals(ParseMode.WORKSPACE, request.parseMode)
                        assertTrue(support in request.workspaceSources)
                        assertEquals(text, request.content)
                        Files.writeString(support, "class Support { void broken( }")
                        return try {
                            native.parse(request)
                        } finally {
                            Files.writeString(support, supportText)
                        }
                    }
                }
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val service =
            GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.getWorkspaceCompiler().compileWorkspace()
            assertTrue(compilation.compile(target, text).isSuccess)
            assertTrue(compilation.compile(caller, callerText).isSuccess)
            val document = TextDocumentIdentifier(target.toString())
            val method = ReferenceParams(document, Position(0, text.indexOf("work")), ReferenceContext(false))
            val expectedCaller = location(caller.toString(), callerText.indexOf("work"), "work")
            assertEquals(listOf(expectedCaller), service.references(method).get(15, TimeUnit.SECONDS))
            assertRecoveredPublication(compilation, target, support, recover)
            val use = location(target.toString(), text.lastIndexOf("count"), "count")
            val declaration = location(target.toString(), text.indexOf("count"), "count")
            val local = DefinitionParams(document, use.range.start)
            assertEquals(listOf(declaration), service.definition(local).get(15, TimeUnit.SECONDS).left)
            val localUses = ReferenceParams(document, declaration.range.start, ReferenceContext(false))
            assertEquals(listOf(use), service.references(localUses).get(15, TimeUnit.SECONDS))
            val failure = assertThrows<ExecutionException> { service.references(method).get(15, TimeUnit.SECONDS) }
            assertEquals(
                ResponseErrorCode.RequestFailed.value,
                assertIs<ResponseErrorException>(failure.cause).responseError.code,
            )
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private fun location(uri: String, offset: Int, token: String): Location =
        Location(uri, Range(Position(0, offset), Position(0, offset + token.length)))

    private suspend fun assertRecoveredPublication(
        compilation: GroovyCompilationService,
        target: URI,
        support: Path,
        recover: AtomicBoolean,
    ) {
        val healthy = assertIs<SnapshotQuery.Answer<*>>(
            compilation.referenceReadiness(target).query {
                assertNotNull(it.index.getDocumentSnapshot(target)).document
            },
        ).value
        val conversion =
            assertIs<SnapshotAcquisition.Available>(compilation.acquireSnapshot(target, Phases.CONVERSION))
        assertEquals(Phases.CONVERSION, conversion.snapshot.parseResult.outcome.requestedPhase)
        recover.set(true)
        val partial = assertIs<SnapshotAcquisition.Partial>(compilation.acquireSnapshot(target))
        assertTrue(partial.isCurrent())
        assertFalse(assertNotNull(partial.snapshot.referenceProducer).complete)
        val outcome = partial.snapshot.parseResult.outcome
        assertTrue(outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS)
        assertTrue(outcome.excludedSources.any { it.source == support.toString() })
        val retained = assertIs<SnapshotQuery.Answer<*>>(
            compilation.referenceReadiness(target).query {
                assertNotNull(it.index.getDocumentSnapshot(target)).document
            },
        ).value
        assertSame(healthy, retained)
    }
}
