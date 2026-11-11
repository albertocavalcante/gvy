package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch

class SessionResourcesTest {
    @Test
    fun `transferred resources close in reverse order exactly once`() {
        val closed = mutableListOf<Int>()
        val transferred = SessionResources().use { pending ->
            pending.own(AutoCloseable { closed += 1 })
            pending.own(AutoCloseable { closed += 2 })
            pending.transfer()
        }

        assertThat(closed).isEmpty()
        transferred.close()
        transferred.close()
        assertThat(closed).containsExactly(2, 1)
    }

    @Test
    fun `failed construction preserves its failure and closes every acquired resource`() {
        val closed = mutableListOf<Int>()
        val original = IOException("construction failed")
        val cleanup = IOException("cleanup failed")

        assertThatThrownBy {
            SessionResources().use { pending ->
                pending.own(AutoCloseable { closed += 1 })
                pending.own(
                    AutoCloseable {
                        closed += 2
                        throw cleanup
                    },
                )
                pending.own(AutoCloseable { closed += 3 })
                throw original
            }
        }.isSameAs(original)

        assertThat(original.suppressed).containsExactly(cleanup)
        assertThat(closed).containsExactly(3, 2, 1)
    }

    @Test
    fun `interrupted construction still closes acquired resources before restoring cancellation`() {
        val original = IOException("construction failed")
        val ready = CountDownLatch(0)
        var closed = false

        Thread.currentThread().interrupt()
        try {
            assertThatThrownBy {
                SessionResources().use { pending ->
                    pending.own(
                        AutoCloseable {
                            ready.await()
                            closed = true
                        },
                    )
                    throw original
                }
            }.isSameAs(original)
            assertThat(closed).isTrue()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }
}
