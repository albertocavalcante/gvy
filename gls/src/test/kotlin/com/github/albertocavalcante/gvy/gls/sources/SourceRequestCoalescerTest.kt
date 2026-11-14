// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRequestCoalescerTest {
    @Test
    @Timeout(10)
    fun `concurrent borrowers share one held acquisition`() = runBlocking {
        val coalescer = SourceRequestCoalescer<String, Int>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<Deferred<Int>>()
        val producers = AtomicInteger()

        try {
            requests += async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.acquire("same") {
                    producers.incrementAndGet()
                    started.complete(Unit)
                    withTimeout(5_000) { release.await() }
                    42
                }
            }
            withTimeout(5_000) { started.await() }
            repeat(11) {
                requests += async(start = CoroutineStart.UNDISPATCHED) {
                    coalescer.acquire("same") {
                        producers.incrementAndGet()
                        99
                    }
                }
            }

            assertEquals(1, producers.get())
            assertTrue(requests.all { !it.isCompleted })
            release.complete(Unit)
            assertEquals(List(12) { 42 }, withTimeout(5_000) { requests.awaitAll() })
            assertEquals(1, producers.get())
        } finally {
            release.complete(Unit)
            requests.forEach { it.cancelAndJoin() }
            coalescer.close()
        }
    }

    @Test
    fun `one cancelled borrower preserves another borrower`() = runBlocking {
        SourceRequestCoalescer<String, Int>().use { coalescer ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val joined = CompletableDeferred<Unit>()
            val first = async {
                coalescer.acquire("same") {
                    started.complete(Unit)
                    release.await()
                    42
                }
            }
            started.await()
            val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                joined.complete(Unit)
                coalescer.acquire("same") { error("A second producer must not start") }
            }
            joined.await()
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals(42, second.await())
        }
    }
}
