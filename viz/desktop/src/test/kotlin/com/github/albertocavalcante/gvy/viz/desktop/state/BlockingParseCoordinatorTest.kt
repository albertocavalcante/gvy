// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockingParseCoordinatorTest {
    @Test
    fun `blocking old parse keeps one worker and only newest request publishes`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val started = mutableListOf<String>()
        val outputs = mutableListOf<String>()
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val coordinator = ParseCoordinator(scope, worker, { input, _ ->
            val now = active.incrementAndGet()
            maximum.accumulateAndGet(now, ::maxOf)
            started.add(input.source)
            if (input.source == "A") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            active.decrementAndGet()
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, {
            outputs.add(requireNotNull(it.tree).id)
            published.countDown()
        }, 0)
        try {
            runBlocking(ui) { coordinator.submit(ParseSnapshot("A", ParserType.CORE, null), false) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            runBlocking(ui) {
                coordinator.submit(ParseSnapshot("B", ParserType.CORE, null), false)
                coordinator.submit(ParseSnapshot("C", ParserType.CORE, null), false)
                yield()
            }
            release.countDown()
            assertTrue(published.await(5, TimeUnit.SECONDS))
            runBlocking(ui) { coordinator.close() }
            assertEquals(listOf("A", "C"), started)
            assertEquals(listOf("C"), outputs)
            assertEquals(1, maximum.get())
        } finally {
            release.countDown()
            scope.cancel()
            worker.close()
            ui.close()
        }
    }
}
