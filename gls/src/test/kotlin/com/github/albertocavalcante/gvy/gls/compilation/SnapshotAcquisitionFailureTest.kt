// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SnapshotAcquisitionFailureTest {
    enum class Failure { FIRST, CACHE_REUSE, HANDOFF, CURRENT_IO, FATAL }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `acquisition validates caller cancellation even when strategy lookup throws`(
        failure: Failure,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val uri = root.resolve("Acquire.groovy").toUri()
        val documents = DocumentProvider().apply { put(uri, "class Acquire {}") }
        val active = ThreadLocal<Job?>()
        var caller: Job? = null
        var lookups = 0
        val exception = IOException("strategy lookup")
        val fatal = AssertionError("fatal strategy failure")
        val at = if (failure == Failure.CACHE_REUSE || failure == Failure.HANDOFF) 3 else 1
        val strategy = object : ProjectStrategy {
            override val id = "acquisition-failure"
            override val displayName = "Acquisition failure"
            override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
            override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
            override fun overridesClasspath(uri: URI) = true
            override fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>): List<Path> {
                val job = active.get()
                if (job != null && job === caller && ++lookups == at) {
                    if (failure != Failure.CURRENT_IO) job.cancel()
                    if (failure == Failure.FATAL) throw fatal
                    throw exception
                }
                return emptyList()
            }
        }
        val registry = ProjectStrategyRegistry(listOf(strategy)).apply {
            selectStrategies(root, ServerConfiguration())
        }
        val manager = WorkspaceManager().apply { setStrategyRegistry(registry) }
        val compilation = GroovyCompilationService(documentProvider = documents, workspaceManager = manager)
        val tracking = object : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
            override fun updateThreadContext(context: CoroutineContext): Job? =
                active.get().also { active.set(context.job) }
            override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) = active.set(oldState)
        }
        try {
            if (failure == Failure.CACHE_REUSE) {
                assertIs<SnapshotAcquisition.Ready>(compilation.acquireEditorSnapshot(uri))
            }
            var observed: Throwable? = null
            launch(tracking) {
                caller = coroutineContext.job
                // Inspect the actual acquisition exception before this cancelled job reaches its completion.
                observed = runCatching {
                    if (failure == Failure.FIRST) {
                        compilation.acquireEditorSnapshot(uri)
                    } else {
                        compilation.queryCapturedSnapshot(
                            compilation.captureSnapshotSource(uri),
                            Phases.CANONICALIZATION,
                            null,
                        ) {
                            error("the caller's query cannot start")
                        }
                    }
                }.exceptionOrNull()
            }.join()
            assertEquals(at, lookups)
            when {
                failure == Failure.CURRENT_IO -> assertSame(exception, observed)
                failure == Failure.FATAL -> assertSame(fatal, observed)
                else -> assertIs<CancellationException>(observed)
            }
        } finally {
            compilation.shutdown()
        }
    }

    private companion object Key : CoroutineContext.Key<ThreadContextElement<Job?>>
}
