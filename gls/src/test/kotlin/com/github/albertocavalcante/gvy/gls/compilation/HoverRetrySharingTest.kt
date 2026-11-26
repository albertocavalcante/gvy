// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.providers.hover.HoverRequestHandler
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HoverRetrySharingTest {
    @Test
    fun `concurrent hover waiters share one fresh compatible producer after startup retirement`(): Unit = runBlocking {
        val uri = URI.create("file:///Example.groovy")
        val source = "class Example { String value() { 'hello' } }"
        val docs = DocumentProvider().apply { put(uri, source) }
        val calls = AtomicInteger()
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val releaseFresh = CountDownLatch(1)
        val service = GroovyCompilationService(documentProvider = docs, workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    val first = calls.incrementAndGet() == 1
                    val parsed = delegate.parse(if (first) request.copy(content = source.replace("String", "Object")) else request)
                    if (first) oldEntered.countDown()
                    check((if (first) releaseOld else releaseFresh).await(10, TimeUnit.SECONDS))
                    return parsed
                }
            }
        })
        val handler = HoverRequestHandler(service, docs)
        val params = HoverParams(TextDocumentIdentifier(uri.toString()), Position(0, source.indexOf("value")))
        fun waiters(delivery: PendingDelivery?): Int {
            if (delivery == null) return 0
            val field = delivery.javaClass.getDeclaredField("waiters").apply { isAccessible = true }
            return synchronized(delivery) { field.getInt(delivery) }
        }
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { handler.getHover(params) }
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) { handler.getHover(params) }
            val registry = service.compilationCacheService.snapshotJobs
            val old = assertNotNull(registry[uri])
            assertEquals(2, waiters(old.delivery))
            service.clearCaches()
            releaseOld.countDown()
            withTimeout(5_000) {
                // Both legal registration and the buggy duplicate-worker path have observable witnesses.
                while (calls.get() < 3 && (calls.get() < 2 || waiters(registry[uri]?.delivery) < 2)) yield()
            }
            assertEquals(2, calls.get(), "One old and one fresh producer for both requests")
            assertEquals(2, waiters(registry[uri]?.delivery))
            first.cancel()
            releaseFresh.countDown()
            assertFailsWith<CancellationException> { first.await() }
            assertContains(second.await().contents.right.value, "String value()")
            assertEquals(2, calls.get())
        } finally {
            releaseOld.countDown()
            releaseFresh.countDown()
            service.shutdown()
        }
    }
}
