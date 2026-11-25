// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Spliterator
import java.util.Spliterators
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnapshotWaiterContractTest {
    private val target = URI.create("file:///SnapshotTarget.groovy")
    private val source = "class SnapshotTarget {}"
    private val documents = DocumentProvider()

    @TempDir
    lateinit var tempDir: Path

    private fun service(
        afterParse: (ParseResult) -> Unit = {},
        beforeParse: (ParseRequest) -> Unit = {},
    ): GroovyCompilationService {
        val service = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        beforeParse(request)
                        return delegate.parse(request).also(afterParse)
                    }
                }
            },
        )
        service.onDocumentOpened(target)
        documents.put(target, source)
        return service
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `interleaved requests share an older compatible worker`(finishNewer: Boolean): Unit = runBlocking {
        val calls = AtomicInteger()
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val service = service { request ->
            calls.incrementAndGet()
            val (entered, release) = if (request.compilePhase == Phases.CONVERSION) {
                first to releaseFirst
            } else {
                second to releaseSecond
            }
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        try {
            val a = async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(target, Phases.CONVERSION) }
            assertTrue(first.await(5, TimeUnit.SECONDS))
            val b = async(start = CoroutineStart.UNDISPATCHED) {
                service.acquireSnapshot(target, Phases.CANONICALIZATION)
            }
            assertTrue(second.await(5, TimeUnit.SECONDS))
            if (finishNewer) {
                releaseSecond.countDown()
                assertIs<SnapshotAcquisition.Ready>(b.await())
            }
            val again = async(start = CoroutineStart.UNDISPATCHED) {
                service.acquireSnapshot(target, Phases.CONVERSION)
            }
            releaseFirst.countDown()
            releaseSecond.countDown()
            val original = assertIs<SnapshotAcquisition.Ready>(a.await())
            val joined = assertIs<SnapshotAcquisition.Ready>(again.await())
            assertIs<SnapshotAcquisition.Ready>(b.await())
            assertSame(original.snapshot.parseResult, joined.snapshot.parseResult)
            assertEquals(2, calls.get())
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            service.shutdown()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["invalidate", "open", "close unchanged"])
    fun `another file retirement wakes a held waiter`(operation: String): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = service { request ->
            if (request.uri == target) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val other = tempDir.resolve("Other.groovy")
        val uri = other.toUri()
        try {
            if (operation == "close unchanged") {
                val text = "class Other {}"
                Files.writeString(other, text)
                service.onDocumentOpened(uri)
                documents.put(uri, text)
                service.compile(uri, text)
            }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(target) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            when (operation) {
                "invalidate" -> service.invalidateCache(uri)
                "open" -> service.onDocumentOpened(uri)
                else -> {
                    documents.remove(uri)
                    service.onClosedSourceChanged(uri)
                }
            }
            assertIs<SnapshotAcquisition.Stale>(withTimeout(2_000) { waiter.await() })
        } finally {
            release.countDown()
            service.shutdown()
        }
    }

    @Test
    fun `a current worker cancellation propagates`(): Unit = runBlocking {
        val service = service { throw CancellationException("worker timed out") }
        try {
            val error = assertFailsWith<CancellationException> { service.acquireSnapshot(target) }
            assertEquals("worker timed out", error.message)
        } finally {
            service.shutdown()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sequence", "iterator", "stream", "iterable", "spliterator"])
    fun `a query cannot return a deferred traversal`(kind: String): Unit = runBlocking {
        val service = service()
        try {
            assertFailsWith<IllegalStateException> {
                service.querySnapshot(target) { snapshot ->
                    when (kind) {
                        "sequence" -> sequence { yield(snapshot.sourceText) }
                        "iterator" -> iterator { yield(snapshot.sourceText) }
                        "iterable" -> sequence { yield(snapshot.sourceText) }.asIterable()
                        "spliterator" -> Spliterators.spliteratorUnknownSize(
                            iterator { yield(snapshot.sourceText) },
                            Spliterator.ORDERED,
                        )
                        else -> Stream.generate { snapshot.sourceText }
                    }
                }
            }
            Unit
        } finally {
            service.shutdown()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `discarded native results close their loader`(cancelProducer: Boolean): Unit = runBlocking {
        val marker = "discarded-loader.marker"
        Files.writeString(tempDir.resolve(marker), "unpublished")
        val parsed = AtomicReference<ParseResult>()
        val finished = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = service(afterParse = { result ->
            val loader = result.compilationUnit.classLoader
            loader.addClasspath(tempDir.toString())
            assertNotNull(loader.findResource(marker))
            parsed.set(result)
            finished.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })
        try {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(target) }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            val producer = assertNotNull(service.compilationCacheService.snapshotJobs[target]).deferred
            if (cancelProducer) service.invalidateCache(target) else documents.put(target, "class Replacement {}")
            release.countDown()
            assertIs<SnapshotAcquisition.Stale>(waiter.await())
            producer.join()
            assertNull(parsed.get().compilationUnit.classLoader.findResource(marker))
        } finally {
            release.countDown()
            service.shutdown()
        }
    }

    @Test
    fun `a rejected stream closes once and preserves the rejection`(): Unit = runBlocking {
        val service = service()
        val closes = AtomicInteger()
        try {
            val error = assertFailsWith<IllegalStateException> {
                service.querySnapshot(target) {
                    Stream.of("unused").onClose {
                        closes.incrementAndGet()
                        error("stream close failed")
                    }
                }
            }
            assertEquals(1, closes.get())
            assertEquals("Consume traversal results inside the snapshot query", error.message)
            assertEquals("stream close failed", error.suppressed.single().message)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `cancellation after compute closes the returned stream once`(): Unit = runBlocking {
        val service = service()
        val closes = AtomicInteger()
        try {
            val child = async {
                service.querySnapshot(target) {
                    currentCoroutineContext().cancel(CancellationException("query cancelled after compute"))
                    Stream.of("unused").onClose {
                        closes.incrementAndGet()
                        error("stream close failed")
                    }
                }
            }
            val error = assertFailsWith<CancellationException> { child.await() }
            assertEquals("query cancelled after compute", error.message)
            assertEquals(1, closes.get())
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `directories are missing sources without worker execution`(): Unit = runBlocking {
        val calls = AtomicInteger()
        val service = service { calls.incrementAndGet() }
        try {
            assertIs<SnapshotAcquisition.Missing>(service.acquireSnapshot(tempDir.toUri()))
            assertEquals(0, calls.get())
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `a retired capture cannot adopt a newer compatible producer`(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val service = service {
            if (calls.incrementAndGet() == 1) entered.countDown() else replacementEntered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(target) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val jobs = service.compilationCacheService.snapshotJobs
            val earlier = assertNotNull(jobs[target]).identity
            service.invalidateCache(URI.create("file:///Other.groovy"))
            val second = async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(target) }
            assertTrue(replacementEntered.await(5, TimeUnit.SECONDS))
            val later = assertNotNull(jobs[target]).identity
            assertFalse(earlier.isCurrent())
            assertTrue(later.isCurrent())
            assertFalse(earlier.canShare(later))
            release.countDown()
            assertIs<SnapshotAcquisition.Stale>(first.await())
            assertIs<SnapshotAcquisition.Ready>(second.await())
        } finally {
            release.countDown()
            service.shutdown()
        }
    }

    @Test
    fun `a regular file symlink supports materialized collection answers`(): Unit = runBlocking {
        val service = service()
        val original = Files.writeString(tempDir.resolve("Linked.groovy"), "class Linked {}")
        val link = Files.createSymbolicLink(tempDir.resolve("Alias.groovy"), original)
        try {
            val result = assertIs<SnapshotQuery.Answer<*>>(
                service.querySnapshot(link.toUri()) { setOf(it.sourceText) },
            )
            assertEquals(setOf("class Linked {}"), result.value)
        } finally {
            service.shutdown()
        }
    }
}
