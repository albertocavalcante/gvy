// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.providers.testing.TestDiscoveryProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.junit.JUnit5TestDetector
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverySharingTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `overlapping discovery shares structural compilation and keeps a surviving caller`(
        cancelFirst: Boolean,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val source = "class Cases { @org.junit.jupiter.api.Test void recoveredTest() {} }"
        val uri = Files.writeString(root.resolve("Subject.groovy"), source).toUri()
        val documents = DocumentProvider().apply { put(uri, source) }
        val work = ObservedRecovery()
        val compilation = GroovyCompilationService(
            DiscoverySharingTest::class.java.classLoader,
            documentProvider = documents,
            workerSessionDecorator = work::decorate,
        )
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.onDocumentOpened(uri)
            val workspace = root.toUri().toString()
            val firstRegistry = registry()
            val secondRegistry = registry()
            val first = async(Dispatchers.Default) {
                TestDiscoveryProvider(compilation, firstRegistry).discoverTests(workspace)
            }
            assertTrue(work.entered.await(10, TimeUnit.SECONDS), "The first recovery never started")
            // The open buffer and completed canonical snapshot take no IO suspension. The second request
            // reaches either its own synchronous recovery or the wait for the already-running one.
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                TestDiscoveryProvider(compilation, secondRegistry).discoverTests(workspace)
            }
            if (cancelFirst) first.cancel()
            work.release.countDown()
            val suite = withTimeout(10_000) { second.await() }.single()
            assertEquals("Cases", suite.suite)
            assertEquals(listOf("recoveredTest"), suite.tests.map { it.test })
            withTimeout(10_000) { first.join() }
            if (cancelFirst) {
                assertTrue(first.isCancelled)
            } else {
                assertEquals(listOf(suite), first.await())
            }
            verify(exactly = if (cancelFirst) 0 else 1) { firstRegistry.extractTests(any(), any(), any()) }
            verify(exactly = 1) { secondRegistry.extractTests(any(), any(), any()) }
            assertEquals(1, work.canonical.get())
            assertEquals(1, work.conversions.get(), "Compatible callers must share the original CONVERSION")
            assertEquals(1, work.builds.get(), "Compatible callers must share symbol construction")
        } finally {
            work.release.countDown()
            compilation.shutdown()
        }
    }

    private fun registry() = spyk(TestFrameworkRegistry().apply { registerIfAbsent(JUnit5TestDetector()) })

    private class ObservedRecovery {
        val canonical = AtomicInteger()
        val conversions = AtomicInteger()
        val builds = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun decorate(worker: WorkerSession) = object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                if (request.compilePhase == Phases.CANONICALIZATION) {
                    canonical.incrementAndGet()
                    return worker.parse(request.copy(content = "def placeholder() {}"))
                }
                check(request.compilePhase == Phases.CONVERSION)
                if (conversions.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "First recovery was never released" }
                }
                val parsed = worker.parse(request)
                val model = parsed.astModel
                return parsed.copy(
                    astModel = object : GroovyAstModel by model {
                        override fun getAllClassNodes(): List<ClassNode> {
                            builds.incrementAndGet()
                            return model.getAllClassNodes()
                        }
                    },
                )
            }
        }
    }
}
