package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolutionCancellationTest {

    @Test
    fun `listeners run once on cancel, and cancel is idempotent`() {
        val cancellation = ResolutionCancellation()
        val runs = AtomicInteger()
        cancellation.onCancel { runs.incrementAndGet() }

        cancellation.cancel()
        cancellation.cancel()

        assertEquals(1, runs.get())
        assertTrue(cancellation.isCancelled)
    }

    @Test
    fun `a listener registered after cancel runs immediately`() {
        val cancellation = ResolutionCancellation().apply { cancel() }
        val runs = AtomicInteger()

        cancellation.onCancel { runs.incrementAndGet() }

        assertEquals(1, runs.get())
    }

    @Test
    fun `a closed listener is not run`() {
        val cancellation = ResolutionCancellation()
        val runs = AtomicInteger()

        cancellation.onCancel { runs.incrementAndGet() }.close()
        cancellation.cancel()

        assertEquals(0, runs.get())
    }

    @Test
    fun `await returns false on timeout and true once cancelled`() {
        val cancellation = ResolutionCancellation()

        assertFalse(cancellation.awaitCancellation(timeoutMs = 1))
        cancellation.cancel()
        assertTrue(cancellation.awaitCancellation(timeoutMs = 0))
    }

    @Test
    fun `throwIfCancelled throws only after cancel`() {
        val cancellation = ResolutionCancellation()

        cancellation.throwIfCancelled()
        cancellation.cancel()

        assertThrows<CancellationException> { cancellation.throwIfCancelled() }
    }
}
