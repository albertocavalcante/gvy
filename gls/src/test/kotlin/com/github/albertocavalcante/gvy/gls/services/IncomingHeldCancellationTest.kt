// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as AstPosition

class IncomingHeldCancellationTest {
    enum class CheckedSource { ORIGIN, CALLER }

    @ParameterizedTest
    @EnumSource(CheckedSource::class)
    fun `cancellation during a held source freshness check is not ContentModified`(
        checked: CheckedSource,
        @TempDir root: Path,
    ) {
        val origin = URI.create("file:///CancelTarget.groovy")
        val caller = URI.create("file:///CancelCaller.groovy")
        val target = if (checked == CheckedSource.ORIGIN) origin else caller
        val activeJob = ThreadLocal<Job?>()
        val armed = AtomicBoolean()
        val triggered = AtomicBoolean()
        val completion = CompletableFuture<Throwable?>()
        val strategy = object : ProjectStrategy {
            override val id = "held-cancellation"
            override val displayName = "Held cancellation"
            override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
            override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
            override fun overridesClasspath(uri: URI) = true
            override fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>): List<Path> {
                if (uri == target && armed.compareAndSet(true, false)) {
                    triggered.set(true)
                    val job =
                        checkNotNull(activeJob.get()) { "freshness check did not run in the requesting coroutine" }
                    job.invokeOnCompletion { cause -> completion.complete(cause) }
                    job.cancel()
                    return listOf(root)
                }
                return emptyList()
            }
        }
        val registry = ProjectStrategyRegistry(listOf(strategy)).apply { selectStrategies(root, ServerConfiguration()) }
        val manager = WorkspaceManager().apply { setStrategyRegistry(registry) }
        val documents = DocumentProvider().apply {
            put(origin, "class Target { def work() {} }\n", DocumentVersion.of(1))
            put(caller, "class Caller { def run(Target x) { x.work() } }\n", DocumentVersion.of(1))
        }
        val tracking = object : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
            override fun updateThreadContext(context: CoroutineContext): Job? {
                val old = activeJob.get()
                activeJob.set(context[Job])
                return old
            }

            override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) {
                activeJob.set(oldState)
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + tracking)
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        val model = parsed.astModel
                        return parsed.copy(
                            astModel = object : GroovyAstModel by model {
                                override fun getNodeAt(uri: URI, position: AstPosition): ASTNode? {
                                    val result = model.getNodeAt(uri, position)
                                    if (uri == caller) armed.set(true)
                                    return result
                                }
                            },
                        )
                    }
                }
            },
        )
        try {
            compilation.onDocumentOpened(origin)
            compilation.onDocumentOpened(caller)
            val service =
                GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
            val item = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(origin.toString()), Position(0, 20)),
            ).get(10, TimeUnit.SECONDS).single()
            val future = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
            val outcome = runCatching { future.get(10, TimeUnit.SECONDS) }
            assertTrue(triggered.get(), "the selected held-source freshness check must cancel its own coroutine")
            val cause = assertIs<CancellationException>(completion.get(10, TimeUnit.SECONDS))
            assertTrue(
                cause.suppressed.none {
                    it is ResponseErrorException && it.responseError.code == ResponseErrorCode.ContentModified.value
                },
                "a retired predicate must not replace cancellation with ContentModified",
            )
            assertIs<CancellationException>(outcome.exceptionOrNull())
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private companion object Key : CoroutineContext.Key<ThreadContextElement<Job?>>
}
