// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.coroutines.asCoroutineDispatcher
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileAsyncResponsivenessTest {
    @Test
    fun `blocked file read leaves EDT responsive and later publishes its result`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val heartbeat = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val readOnEdt = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val model = AtomicReference<AstViewModel?>()
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val ui = ObservingAwtDispatcher {
            val value = model.get()
            if (value != null && !value.isParsing && value.astTree?.id == "loaded") finished.countDown()
        }
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String {
                readOnEdt.set(EventQueue.isDispatchThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return "loaded"
            }
            override suspend fun write(file: File, contents: String) = Unit
        }
        try {
            EventQueue.invokeLater {
                try {
                    val value = AstViewModel(ui, worker, { input, _ ->
                        ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
                    }, 0, FileEnvironment(worker, files, InMemoryPreferences()))
                    model.set(value)
                    value.loadFile(File("gated.groovy"))
                } catch (caught: Throwable) {
                    failure.set(caught)
                } finally {
                    returned.countDown()
                    EventQueue.invokeLater { heartbeat.countDown() }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(returned.await(5, TimeUnit.SECONDS), "Load request blocked the EDT")
            assertTrue(heartbeat.await(5, TimeUnit.SECONDS), "EDT heartbeat did not run during read")
            assertNull(failure.get())
            assertFalse(readOnEdt.get())
            EventQueue.invokeAndWait {
                val value = requireNotNull(model.get())
                assertTrue(value.isParsing)
                assertEquals("", value.sourceCode)
            }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS), "Loaded result was not published")
            EventQueue.invokeAndWait {
                val value = requireNotNull(model.get())
                assertEquals("loaded", value.sourceCode)
                assertEquals("loaded", value.astTree?.id)
            }
        } finally {
            release.countDown()
            EventQueue.invokeAndWait { model.get()?.close() }
            worker.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Worker did not terminate")
        }
    }

    @Test
    fun `fast file read runs off EDT and eventually publishes its result`() {
        val readOnEdt = AtomicBoolean(true)
        val finished = CountDownLatch(1)
        val model = AtomicReference<AstViewModel?>()
        val ui = ObservingAwtDispatcher {
            val value = model.get()
            if (value != null && !value.isParsing && value.astTree?.id == "def value = 1") finished.countDown()
        }
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String {
                readOnEdt.set(EventQueue.isDispatchThread())
                return "def value = 1"
            }
            override suspend fun write(file: File, contents: String) = Unit
        }
        try {
            EventQueue.invokeAndWait {
                val value = AstViewModel(
                    ui,
                    CompletesBeforeReturnDispatcher,
                    { input, _ ->
                        ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
                    },
                    300,
                    FileEnvironment(CompletesBeforeReturnDispatcher, files, InMemoryPreferences()),
                )
                model.set(value)
                value.loadFile(File("fast.groovy"))
                if (!value.isParsing && value.astTree?.id == "def value = 1") finished.countDown()
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS), "Fast file result was not published")
            assertFalse(readOnEdt.get())
            EventQueue.invokeAndWait {
                val value = requireNotNull(model.get())
                assertFalse(value.isParsing)
                assertEquals("def value = 1", value.sourceCode)
                assertEquals("def value = 1", value.astTree?.id)
            }
        } finally {
            EventQueue.invokeAndWait { model.get()?.close() }
        }
    }
}
