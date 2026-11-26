// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HeldReferenceCoverageTest {
    enum class HeldFile { ORIGIN, CALLER }

    @ParameterizedTest
    @EnumSource(HeldFile::class)
    fun `a complete index cannot certify a recovered held parse`(file: HeldFile, @TempDir root: Path): Unit =
        runBlocking {
            Fixture(root).use { fixture ->
                val item = fixture.prepareHealthyJourney()
                val uri = if (file == HeldFile.ORIGIN) fixture.target else fixture.caller
                val indexed = fixture.qualifiedDocument(uri)

                fixture.acquireRecoveredParse(uri)
                assertSame(indexed, fixture.qualifiedDocument(uri), "the original complete index still qualifies")

                val request = fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                val failure = assertThrows<ExecutionException> { request.get(10, TimeUnit.SECONDS) }
                val response = assertIs<ResponseErrorException>(failure.cause).responseError
                assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
            }
        }

    private class Fixture(root: Path) : AutoCloseable {
        val target = root.resolve("Target.groovy").toUri()
        val caller = root.resolve("Caller.groovy").toUri()
        private val support = root.resolve("Support.groovy")
        private val healthySupport = "class Support {}"
        private val targetText = "class Target { def work() {} }"
        private val callerText = "class Caller { def run(Target x) { x.work() } }"
        private val healthyRequests = ConcurrentHashMap<URI, ParseRequest>()
        @Volatile private var recover: URI? = null
        private val documents = DocumentProvider().apply {
            put(target, targetText)
            put(caller, callerText)
        }
        private val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { native ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult = parseWithNative(native, request)
                }
            },
        )
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
        val service = GroovyTextDocumentService(scope, compilation, options)

        init {
            Files.writeString(support, healthySupport)
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
        }

        private fun parseWithNative(native: WorkerSession, request: ParseRequest): ParseResult {
            if (request.compilePhase != Phases.CANONICALIZATION) return native.parse(request)
            if (request.uri != recover) {
                val result = native.parse(request)
                if (result.isSuccessful && !result.outcome.isDegraded) healthyRequests.putIfAbsent(request.uri, request)
                return result
            }

            // The same native worker observes a transient malformed companion at the same listed path.
            // Restore its healthy bytes before freshness checks. Only the parser outcome records the exclusion.
            val original = assertNotNull(healthyRequests[request.uri])
            assertEquals(original.content, request.content)
            assertEquals(original.compilePhase, request.compilePhase)
            assertEquals(original.parseMode, request.parseMode)
            assertEquals(original.classpath, request.classpath)
            assertEquals(original.sourceRoots, request.sourceRoots)
            assertEquals(original.workspaceSources, request.workspaceSources)
            assertEquals(original.openSources, request.openSources)
            assertTrue(support in request.workspaceSources)
            Files.writeString(support, "class Support { void broken( }")
            return try {
                native.parse(request)
            } finally {
                Files.writeString(support, healthySupport)
            }
        }

        suspend fun prepareHealthyJourney(): CallHierarchyItem {
            compilation.getWorkspaceCompiler().compileWorkspace()
            compilation.compile(target, targetText)
            compilation.compile(caller, callerText)
            val position = Position(0, targetText.indexOf("work"))
            val item = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(target.toString()), position),
            ).get(10, TimeUnit.SECONDS).single()
            val request = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
            val incoming = request.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("run"), incoming.map { it.from.name })
            assertEquals(caller.toString(), incoming.single().from.uri)
            assertEquals(listOf(Range(Position(0, 37), Position(0, 41))), incoming.single().fromRanges)
            return item
        }

        suspend fun qualifiedDocument(uri: URI): SemanticDocument {
            val query = compilation.referenceReadiness(target).query { references ->
                assertNotNull(references.index.getDocumentSnapshot(uri)).document
            }
            return assertIs<SnapshotQuery.Answer<SemanticDocument>>(query).value
        }

        suspend fun acquireRecoveredParse(uri: URI) {
            // A conversion acquisition replaces the owned cache slot without modifying the published semantic index.
            val previous = compilation.acquireSnapshot(uri, Phases.CONVERSION)
            val conversion = assertIs<SnapshotAcquisition.Available>(previous)
            assertEquals(Phases.CONVERSION, conversion.snapshot.parseResult.outcome.requestedPhase)
            recover = uri
            val held = assertIs<SnapshotAcquisition.Partial>(compilation.acquireSnapshot(uri))
            assertTrue(held.isCurrent())
            val outcome = held.snapshot.parseResult.outcome
            assertTrue(outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS)
            assertTrue(outcome.excludedSources.any { it.source == support.toString() })
        }

        override fun close() {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
