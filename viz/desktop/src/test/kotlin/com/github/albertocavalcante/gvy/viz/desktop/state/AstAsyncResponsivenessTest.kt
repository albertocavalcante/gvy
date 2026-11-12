package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.coroutines.asCoroutineDispatcher
import java.awt.EventQueue
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

class AstAsyncResponsivenessTest {
    @Test
    fun `blocked parser leaves EDT responsive and later publishes its result`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val heartbeat = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val parseOnEdt = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val model = AtomicReference<AstViewModel?>()
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val ui = ObservingAwtDispatcher {
            val value = model.get()
            if (value != null && !value.isParsing && value.astTree?.id == "source") finished.countDown()
        }
        try {
            EventQueue.invokeLater {
                try {
                    val value = AstViewModel(ui, worker, { input, _ ->
                        parseOnEdt.set(EventQueue.isDispatchThread())
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
                    }, 3_600_000, FileEnvironment(prefs = InMemoryPreferences()))
                    model.set(value)
                    value.updateSourceCode("source")
                    value.parseCode()
                } catch (caught: Throwable) {
                    failure.set(caught)
                } finally {
                    returned.countDown()
                    EventQueue.invokeLater { heartbeat.countDown() }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(returned.await(5, TimeUnit.SECONDS), "Parse request blocked the EDT")
            assertTrue(heartbeat.await(5, TimeUnit.SECONDS), "EDT heartbeat did not run during parse")
            assertNull(failure.get())
            assertFalse(parseOnEdt.get())
            EventQueue.invokeAndWait { assertTrue(requireNotNull(model.get()).isParsing) }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS), "Parse result was not published")
            EventQueue.invokeAndWait { assertEquals("source", requireNotNull(model.get()).astTree?.id) }
        } finally {
            release.countDown()
            EventQueue.invokeAndWait { model.get()?.close() }
            worker.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Worker did not terminate")
        }
    }

    @Test
    fun `fast parser runs off EDT and eventually publishes its result`() {
        val parseOnEdt = AtomicBoolean(true)
        val finished = CountDownLatch(1)
        val model = AtomicReference<AstViewModel?>()
        val ui = ObservingAwtDispatcher {
            val value = model.get()
            if (value != null && !value.isParsing && value.astTree?.id == "source") finished.countDown()
        }
        try {
            EventQueue.invokeAndWait {
                val value = AstViewModel(
                    ui,
                    CompletesBeforeReturnDispatcher,
                    { input, _ ->
                        parseOnEdt.set(EventQueue.isDispatchThread())
                        ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
                    },
                    3_600_000,
                    FileEnvironment(prefs = InMemoryPreferences()),
                )
                model.set(value)
                value.updateSourceCode("source")
                value.parseCode()
                if (!value.isParsing && value.astTree?.id == "source") finished.countDown()
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS), "Fast parse result was not published")
            assertFalse(parseOnEdt.get())
            EventQueue.invokeAndWait {
                val value = requireNotNull(model.get())
                assertFalse(value.isParsing)
                assertEquals("source", value.astTree?.id)
            }
        } finally {
            EventQueue.invokeAndWait { model.get()?.close() }
        }
    }
}
