// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@Timeout(10)
class SourceTypeIndexCacheTest {
    private val empty = SourceTypeIndex { null }

    @Test
    fun `cold equivalent requests share a producer and warm requests reuse the detached index`() = runBlocking {
        val server = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val builds = AtomicInteger()
        try {
            SourceTypeIndexCache(server) { _, _, check ->
                builds.incrementAndGet()
                entered.complete(Unit)
                release.await()
                check()
                empty
            }.use { cache ->
                val first = async { cache.index("groovy", "digest", "source") }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { cache.index("groovy", "digest", "source") }
                release.complete(Unit)
                assertSame(first.await(), second.await())
                assertSame(empty, cache.index("groovy", "digest", "source"))
                assertEquals(1, builds.get())
            }
        } finally {
            server.cancel()
        }
    }

    @Test
    fun `cancelling one request after parse entry preserves another borrower and its producer`() = runBlocking {
        val server = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val builds = AtomicInteger()
        try {
            SourceTypeIndexCache(server) { _, _, check ->
                builds.incrementAndGet()
                entered.complete(Unit)
                release.await()
                check()
                empty
            }.use { cache ->
                val first = async { cache.index("groovy", "digest", "source") }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { cache.index("groovy", "digest", "source") }
                first.cancelAndJoin()
                release.complete(Unit)
                assertSame(empty, second.await())
                assertEquals(1, builds.get())
            }
        } finally {
            server.cancel()
        }
    }

    @Test
    fun `last borrower cancellation stops an entered parse and does not poison the next request`() = runBlocking {
        val server = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val builds = AtomicInteger()
        try {
            SourceTypeIndexCache(server) { _, _, _ ->
                if (builds.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                } else {
                    empty
                }
            }.use { cache ->
                val first = async { cache.index("groovy", "digest", "source") }
                entered.await()
                first.cancelAndJoin()
                stopped.await()
                assertSame(empty, cache.index("groovy", "digest", "source"))
                assertEquals(2, builds.get())
            }
        } finally {
            server.cancel()
        }
    }

    @Test
    fun `server shutdown cancels entered parsers even while a request remains attached`(): Unit = runBlocking {
        val server = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        SourceTypeIndexCache(server) { _, _, _ ->
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }.use { cache ->
            val request = async { cache.index("groovy", "digest", "source") }
            entered.await()
            server.cancel()
            stopped.await()
            assertFailsWith<CancellationException> { request.await() }
        }
    }
}
