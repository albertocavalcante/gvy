// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SourceRequestCoalescerTest {
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
