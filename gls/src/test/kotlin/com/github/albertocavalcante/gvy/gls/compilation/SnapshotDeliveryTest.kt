// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnapshotDeliveryTest {
    @TempDir
    lateinit var tempDir: Path

    enum class Receiver { CANCELLED, SURVIVES, CANCEL_DURING_FINAL_CHECK }

    @ParameterizedTest
    @EnumSource(Receiver::class)
    fun `an uncached producer closes its loader only if nobody receives the result`(receiver: Receiver): Unit =
        runBlocking {
            val marker = "snapshot-delivery.marker"
            val jar = markerJar(marker)
            val uri = URI.create("file:///Delivery.groovy")
            val documents = DocumentProvider().apply { put(uri, "class Delivery {}") }
            val held = HeldConversion(jar, marker)
            val service = GroovyCompilationService(
                documentProvider = documents,
                ioDispatcher = held,
                workerSessionDecorator = held::decorate,
            )
            val checking = AtomicReference<() -> Unit>({})
            service.workspaceManager.setTestClasspath(tempDir) {
                checking.get().invoke()
                emptyList()
            }
            val returned = AtomicReference<SnapshotAcquisition>()
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    service.acquireSnapshot(uri, Phases.CONVERSION).also(returned::set)
                }
                assertTrue(held.entered.await(5, TimeUnit.SECONDS))
                val survivor = if (receiver == Receiver.SURVIVES) {
                    async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(uri, Phases.CONVERSION) }
                } else {
                    null
                }
                val winner = assertIs<SnapshotAcquisition.Ready>(
                    withTimeout(5_000) { service.acquireSnapshot(uri, Phases.CANONICALIZATION) },
                )
                if (receiver != Receiver.CANCEL_DURING_FINAL_CHECK) first.cancelAndJoin()
                held.release.countDown()
                assertTrue(held.finished.await(5, TimeUnit.SECONDS))
                val loader = held.parsed.get().compilationUnit.classLoader
                val cached = assertNotNull(service.compilationCacheService.getOwned(uri))
                assertSame(winner.snapshot.parseResult, cached.snapshot.parseResult)
                if (receiver == Receiver.CANCEL_DURING_FINAL_CHECK) {
                    // The producer has completed. Only the waiter on this event loop will recheck these inputs now.
                    checking.set { first.cancel() }
                    withTimeout(5_000) { first.join() }
                    assertTrue(first.isCancelled)
                    assertNull(returned.get(), "A cancelled acquisition must not deliver its parser")
                }
                if (survivor == null) {
                    assertNull(loader.findResource(marker))
                } else {
                    val received = assertIs<SnapshotAcquisition.Ready>(withTimeout(5_000) { survivor.await() })
                    assertSame(held.parsed.get(), received.snapshot.parseResult)
                    assertNotNull(received.snapshot.parseResult.compilationUnit.classLoader.findResource(marker))
                }
            } finally {
                held.release.countDown()
                service.shutdown()
                held.parsed.get()?.compilationUnit?.classLoader?.close()
            }
        }

    private fun markerJar(marker: String): Path {
        val jar = tempDir.resolve("delivery.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry(marker))
            it.write(1)
            it.closeEntry()
        }
        return jar
    }

    @Test
    fun `a cache hit checks cancellation after its final freshness lookup`(): Unit = runBlocking {
        val uri = URI.create("file:///Cached.groovy")
        val documents = DocumentProvider().apply { put(uri, "class Cached {}") }
        val service = GroovyCompilationService(documentProvider = documents)
        val count = AtomicInteger()
        var onLookup: (Int) -> Unit = {}
        service.workspaceManager.setTestClasspath(tempDir) {
            onLookup(count.incrementAndGet())
            emptyList()
        }
        try {
            val seeded = assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))
            count.set(0)
            val cached = assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))
            assertSame(seeded.snapshot.parseResult, cached.snapshot.parseResult)
            val lastLookup = count.get()
            assertTrue(lastLookup > 0)
            count.set(0)
            val returned = AtomicReference<SnapshotAcquisition>()
            val caller = async {
                val job = currentCoroutineContext().job
                onLookup = { lookup -> if (lookup == lastLookup) job.cancel() }
                returned.set(service.acquireSnapshot(uri))
            }
            withTimeout(5_000) { caller.join() }
            assertEquals(lastLookup, count.get())
            assertTrue(caller.isCancelled)
            assertNull(returned.get(), "The cache hit cannot return after the caller cancelled in its final lookup")
        } finally {
            service.shutdown()
        }
    }

    /** Observe the worker runnable returning, after its coroutine and completion handlers have finished. */
    private class HeldConversion(private val jar: Path, private val marker: String) : CoroutineDispatcher() {
        private val producer = AtomicReference<Thread>()
        val parsed = AtomicReference<ParseResult>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)

        fun decorate(delegate: WorkerSession): WorkerSession = object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                val result = delegate.parse(request)
                if (request.compilePhase == Phases.CONVERSION) {
                    producer.set(Thread.currentThread())
                    val loader = result.compilationUnit.classLoader
                    loader.addURL(jar.toUri().toURL())
                    parsed.set(result)
                    assertNotNull(loader.findResource(marker))
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return result
            }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) = Dispatchers.IO.dispatch(context) {
            try {
                block.run()
            } finally {
                if (Thread.currentThread() === producer.get()) finished.countDown()
            }
        }
    }
}
