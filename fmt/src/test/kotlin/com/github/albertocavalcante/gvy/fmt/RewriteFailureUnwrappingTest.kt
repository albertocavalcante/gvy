// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.openrewrite.Cursor
import org.openrewrite.internal.RecipeRunException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RewriteFailureUnwrappingTest {
    @Test
    fun `cleanup evidence on a known wrapper reaches its original failure once`() {
        val failure = IOException("recipe failed")
        val cleanup = IOException("stream close failed")
        val wrapped = wrap(failure).apply { addSuppressed(cleanup) }

        assertThat(wrapped.originalRecipeFailure()).isSameAs(failure)
        assertThat(wrapped.originalRecipeFailure()).isSameAs(failure)
        assertThat(failure.suppressed).containsExactly(cleanup)
    }

    @Test
    fun `nested wrappers preserve independent evidence without adding cause cycles`() {
        val failure = IOException("recipe failed")
        val existing = IOException("existing cleanup failure")
        val innerCleanup = IOException("inner stream close failed")
        val outerCleanup = IOException("outer stream close failed")
        failure.addSuppressed(existing)
        val inner = wrap(failure).apply { addSuppressed(innerCleanup) }
        val outer = wrap(inner).apply {
            addSuppressed(existing)
            addSuppressed(outerCleanup)
            addSuppressed(inner)
            addSuppressed(wrap(failure))
        }

        assertThat(outer.originalRecipeFailure()).isSameAs(failure)
        assertThat(outer.originalRecipeFailure()).isSameAs(failure)
        assertThat(failure.suppressed).containsExactly(existing, innerCleanup, outerCleanup)
    }

    @TestFactory
    fun `critical failure identity and independent cleanup evidence both survive`(): List<DynamicTest> = listOf(
        CancellationException("cancelled"),
        LinkageError("fatal"),
    ).map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val cleanup = IOException("cleanup failed")
            val wrapped = wrap(failure).apply { addSuppressed(cleanup) }

            assertThat(wrapped.originalRecipeFailure()).isSameAs(failure)
            assertThat(failure.suppressed).containsExactly(cleanup)
        }
    }

    @Test
    fun `an ordinary exception that is not an engine wrapper stays unchanged`() {
        val failure = IOException("engine failed")
        val cleanup = IOException("cleanup failed")
        failure.addSuppressed(cleanup)

        assertThat(failure.originalRecipeFailure()).isSameAs(failure)
        assertThat(failure.suppressed).containsExactly(cleanup)
    }

    @Test
    fun `concurrent callbacks retain shared cleanup evidence once`() {
        val failure = IOException("recipe failed")
        val barrier = CyclicBarrier(WORKER_COUNT)
        val reads = AtomicInteger()
        val cleanup = object : IOException("cleanup failed") {
            override val cause: Throwable?
                get() {
                    if (reads.incrementAndGet() <= WORKER_COUNT) barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    return null
                }
        }
        val wrapped = wrap(failure).apply { addSuppressed(cleanup) }
        val workers = Executors.newFixedThreadPool(WORKER_COUNT)
        try {
            val results = (0 until WORKER_COUNT).map { workers.submit<Throwable> { wrapped.originalRecipeFailure() } }
            results.forEach { assertThat(it.get(WAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(failure) }

            assertThat(failure.suppressed).containsExactly(cleanup)
        } finally {
            workers.shutdownNow()
            assertThat(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun wrap(failure: Throwable) = RecipeRunException(failure, Cursor(null, "failure-control"))

    private companion object {
        const val WAIT_SECONDS = 5L
        const val WORKER_COUNT = 2
    }
}
