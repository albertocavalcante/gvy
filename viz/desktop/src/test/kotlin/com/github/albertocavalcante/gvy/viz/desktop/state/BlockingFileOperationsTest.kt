// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockingFileOperationsTest {
    @Test
    fun `blocked read runs alone and latest request replaces queued reads`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parsed = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val started = Collections.synchronizedList(mutableListOf<String>())
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String {
                val now = active.incrementAndGet()
                maximum.accumulateAndGet(now, ::maxOf)
                started.add(file.name)
                if (file.name == "A") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                active.decrementAndGet()
                return file.name
            }
            override suspend fun write(file: File, contents: String) = Unit
        }
        val uiExecutor = Executors.newSingleThreadExecutor()
        val ioExecutor = Executors.newFixedThreadPool(3)
        val ui = uiExecutor.asCoroutineDispatcher()
        val newerDispatchRan = CountDownLatch(1)
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                ioExecutor.execute {
                    block.run()
                    // A cannot reach this point until released; a signal means a later read dispatch ran.
                    newerDispatchRan.countDown()
                }
            }
        }
        val model = AstViewModel(ui, ui, { input, _ ->
            parsed.countDown()
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 0, FileEnvironment(io, files, InMemoryPreferences()))
        try {
            runBlocking(ui) { model.loadFile(File("A")) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            runBlocking(ui) {
                model.loadFile(File("B"))
                model.loadFile(File("C"))
                yield()
            }
            // B is canceled on the UI thread before it can launch. C has reached the IO dispatcher and
            // either suspended at the read mutex or incorrectly entered the read while A is still blocked.
            assertTrue(newerDispatchRan.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("A"), started.toList())
            release.countDown()
            assertTrue(parsed.await(5, TimeUnit.SECONDS))
            runBlocking(ui) { yield() }
            assertEquals(listOf("A", "C"), started.toList())
            assertEquals(1, maximum.get())
            runBlocking(ui) { assertEquals("C", model.astTree?.id) }
        } finally {
            release.countDown()
            runBlocking(ui) { model.close() }
            ioExecutor.shutdown()
            ui.close()
        }
    }
}
