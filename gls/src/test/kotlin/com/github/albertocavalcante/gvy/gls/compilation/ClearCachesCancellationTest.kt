// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A global clear cancels the pending compiles it retires after it leaves the fence, so their cancellation
 * handlers never run while the fence is held (#1342). Their old-epoch tickets cannot commit either way.
 */
class ClearCachesCancellationTest {
    private val uri = URI.create("file:///Pending.groovy")
    private val parsing = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    if (request.uri == uri) {
                        parsing.countDown()
                        check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "parse never released" }
                    }
                    return delegate.parse(request)
                }
            }
        },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun tearDown() {
        release.countDown()
        scope.cancel()
    }

    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun `a retired pending compile is cancelled outside the fence`() {
        documents.put(uri, "class Pending {}", 1)
        val job = service.compileAsync(scope, uri, "class Pending {}")
        assertTrue(parsing.await(WAIT_SECONDS, TimeUnit.SECONDS), "the compile never started parsing")

        // Runs synchronously inside cancel(). Another thread then needs the fence: under the fence it would wait for
        // this thread, which waits for it, so it finishes only once the cancellation runs outside the fence.
        val outcome = AtomicReference<String>()
        job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            val fenced = CountDownLatch(1)
            thread(name = "takes-a-ticket") {
                service.compilationCacheService.ownership.begin(URI.create("file:///Other.groovy"))
                fenced.countDown()
            }
            outcome.set(if (fenced.await(HANDLER_WAIT_SECONDS, TimeUnit.SECONDS)) "outside" else "under the fence")
        }

        service.clearCaches()

        assertEquals("outside", outcome.get(), "the pending compile's cancellation ran while the fence was held")
    }

    private companion object {
        const val WAIT_SECONDS = 10L

        /** Only the failure path waits this long: when the cancellation runs under the fence. */
        const val HANDLER_WAIT_SECONDS = 2L
    }
}
