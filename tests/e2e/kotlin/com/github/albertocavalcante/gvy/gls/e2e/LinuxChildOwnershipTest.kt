package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class LinuxChildOwnershipTest {
    @Test
    fun `every claimed descriptor is released before an empty kernel outcome confirms cleanup`() {
        val kernel = FakeKernel(FIRST_PID, SECOND_PID)

        owner(kernel).release()

        assertThat(kernel.calls).containsSubsequence(
            "claim:$FIRST_PID",
            "release:$FIRST_PID",
            "close:$FIRST_PID",
            "claim:$SECOND_PID",
            "release:$SECOND_PID",
            "close:$SECOND_PID",
            "empty",
        )
        assertThat(kernel.live).isEmpty()
    }

    @Test
    fun `children adopted during a release are drained before final ECHILD`() {
        val kernel = FakeKernel(FIRST_PID)
        kernel.children.getValue(FIRST_PID).onRelease = { it.add(SECOND_PID) }

        owner(kernel).release()

        assertThat(kernel.calls).containsSubsequence("release:$FIRST_PID", "release:$SECOND_PID", "empty")
        assertThat(kernel.live).isEmpty()
    }

    @Test
    fun `an ownership mismatch never signals that PID and still releases known children`() {
        val kernel = FakeKernel(FIRST_PID)
        val mismatch = LinuxOwnershipFailure("PID is outside this supervisor's lineage")
        kernel.reported = { listOf(OUTSIDE_PID) + it.live }
        kernel.claimFailures[OUTSIDE_PID] = mismatch

        assertThatThrownBy { owner(kernel).release() }.isSameAs(mismatch)

        assertThat(kernel.calls).doesNotContain("release:$OUTSIDE_PID", "close:$OUTSIDE_PID")
        assertThat(kernel.calls).contains("release:$FIRST_PID", "close:$FIRST_PID")
        assertThat(kernel.live).isEmpty()
    }

    @Test
    fun `native release failure remains primary with descriptor failure suppressed and peers released`() {
        val kernel = FakeKernel(FIRST_PID, SECOND_PID)
        val releaseFailure = LinuxOwnershipFailure("pidfd signal rejected")
        val closeFailure = LinuxOwnershipFailure("pidfd close failed")
        kernel.children.getValue(FIRST_PID).apply {
            failure = releaseFailure
            closingFailure = closeFailure
        }

        assertThatThrownBy { owner(kernel).release() }.isSameAs(releaseFailure)

        assertThat(releaseFailure.suppressed).containsExactly(closeFailure)
        assertThat(kernel.calls).contains("close:$FIRST_PID", "release:$SECOND_PID", "close:$SECOND_PID")
        assertThat(kernel.live).containsExactly(FIRST_PID)
    }

    @Test
    fun `an empty enumeration cannot certify cleanup while the kernel reports live children`() {
        val kernel = FakeKernel(FIRST_PID)
        kernel.reported = { emptyList() }

        assertThatThrownBy { owner(kernel).release() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("unconfirmed")

        assertThat(kernel.live).containsExactly(FIRST_PID)
    }

    @Test
    fun `one monotonic budget bounds the whole drain rather than restarting per child`() {
        val kernel = FakeKernel(FIRST_PID, SECOND_PID)
        kernel.children.getValue(FIRST_PID).onRelease = { it.clock.set(EXPIRED_NANOS) }

        assertThatThrownBy { owner(kernel, SHORT_BUDGET_NANOS).release() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("unconfirmed")

        assertThat(kernel.calls).contains("close:$FIRST_PID").doesNotContain("claim:$SECOND_PID")
        assertThat(kernel.live).containsExactly(SECOND_PID)
    }

    @Test
    fun `a child already reaped during claim does not prevent final kernel confirmation`() {
        val kernel = FakeKernel(FIRST_PID)
        kernel.reapOnClaim.add(FIRST_PID)

        owner(kernel).release()

        assertThat(kernel.calls).contains("claim:$FIRST_PID", "empty").doesNotContain("release:$FIRST_PID")
    }

    @Test
    fun `observer failure cannot become a successful empty cleanup`() {
        val kernel = FakeKernel()
        val failure = LinuxOwnershipFailure("owned-child observation failed")
        kernel.observationFailure = failure

        assertThatThrownBy { owner(kernel).release() }.isSameAs(failure)
    }

    private fun owner(kernel: FakeKernel, timeoutNanos: Long = TEST_BUDGET_NANOS) = LinuxChildOwnership(
        kernel,
        Duration.ofNanos(timeoutNanos),
        kernel.clock::getAndIncrement,
    )

    private class FakeKernel(vararg initial: Int) : LinuxChildKernel {
        val live = linkedSetOf<Int>()
        val children = mutableMapOf<Int, FakeChild>()
        val calls = mutableListOf<String>()
        val claimFailures = mutableMapOf<Int, LinuxOwnershipFailure>()
        val reapOnClaim = mutableSetOf<Int>()
        val clock = AtomicLong()
        var reported: (FakeKernel) -> List<Int> = { it.live.toList() }
        var observationFailure: LinuxOwnershipFailure? = null

        init {
            initial.forEach(::add)
        }

        fun add(pid: Int) {
            live.add(pid)
            children[pid] = FakeChild(pid, this)
        }

        override fun ownedChildren(): List<Int> {
            observationFailure?.let { throw it }
            return reported(this)
        }

        override fun claimChild(pid: Int): LinuxOwnedChild? {
            calls.add("claim:$pid")
            claimFailures[pid]?.let { throw it }
            if (reapOnClaim.remove(pid)) {
                live.remove(pid)
                return null
            }
            return children.getValue(pid)
        }

        override fun reapAnyExited(): LinuxReapResult = if (live.isEmpty()) {
            calls.add("empty")
            LinuxReapResult.Empty
        } else {
            LinuxReapResult.Running
        }
    }

    private class FakeChild(private val pid: Int, private val kernel: FakeKernel) : LinuxOwnedChild {
        var failure: LinuxOwnershipFailure? = null
        var closingFailure: LinuxOwnershipFailure? = null
        var onRelease: (FakeKernel) -> Unit = {}

        override fun terminateAndReap(timeRemaining: () -> Long) {
            assertThat(timeRemaining()).isPositive()
            kernel.calls.add("release:$pid")
            failure?.let { throw it }
            kernel.live.remove(pid)
            onRelease(kernel)
        }

        override fun close() {
            kernel.calls.add("close:$pid")
            closingFailure?.let { throw it }
        }
    }

    private companion object {
        const val FIRST_PID = 101
        const val SECOND_PID = 102
        const val OUTSIDE_PID = 999
        const val TEST_BUDGET_NANOS = 1000L
        const val SHORT_BUDGET_NANOS = 10L
        const val EXPIRED_NANOS = 20L
    }
}
