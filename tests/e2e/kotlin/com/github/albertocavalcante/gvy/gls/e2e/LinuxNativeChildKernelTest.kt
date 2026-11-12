package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class LinuxNativeChildKernelTest {
    @Test
    fun `preflight arms ownership and closes its capability descriptor before payload permission`() {
        val calls = FakeCalls()
        val kernel = LinuxNativeChildKernel(calls) {
            calls.log.add("observe")
            emptyList()
        }

        kernel.prepare()

        assertThat(
            calls.log,
        ).containsExactly("subreaper", "open:$SUPERVISOR_PID", "close:$DESCRIPTOR", "observe", "wait:-1")
    }

    @Test
    fun `failed subreaper setup cannot silently downgrade or continue the handshake`() {
        val calls = FakeCalls().apply { armResult = LinuxNativeCall(-1, PERMISSION_DENIED) }

        assertThatThrownBy { kernel(calls).prepare() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("subreaper")

        assertThat(calls.log).containsExactly("subreaper")
    }

    @Test
    fun `pidfd capability failure rejects preflight before observing or starting payload children`() {
        val calls = FakeCalls().apply { openResult = LinuxNativeCall(-1, INVALID_ARGUMENT) }

        assertThatThrownBy { kernel(calls).prepare() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("pidfd")

        assertThat(calls.log).containsExactly("subreaper", "open:$SUPERVISOR_PID")
    }

    @Test
    fun `preflight cannot permit payload execution while the kernel already owns a child`() {
        val calls = FakeCalls()
        calls.waitResults.add(LinuxNativeCall(0))

        assertThatThrownBy { kernel(calls).prepare() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("ownership")
    }

    @Test
    fun `a failed capability descriptor close prevents payload permission`() {
        val calls = FakeCalls().apply { closeResult = LinuxNativeCall(-1, IO_ERROR) }

        assertThatThrownBy { kernel(calls).prepare() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("close")

        assertThat(calls.log).containsExactly("subreaper", "open:$SUPERVISOR_PID", "close:$DESCRIPTOR")
    }

    @Test
    fun `a PID outside the supervisor closes its descriptor without signaling`() {
        val calls = FakeCalls()
        calls.waitResults.add(LinuxNativeCall(-1, NO_CHILDREN))

        assertThatThrownBy { kernel(calls).claimChild(CHILD_PID) }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("ownership")

        assertThat(calls.log).containsExactly("open:$CHILD_PID", "wait:$CHILD_PID", "close:$DESCRIPTOR")
    }

    @Test
    fun `a mismatched native child identity closes its descriptor without signaling`() {
        val calls = FakeCalls()
        calls.waitResults.add(LinuxNativeCall(OTHER_PID))

        assertThatThrownBy { kernel(calls).claimChild(CHILD_PID) }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("ownership")

        assertThat(calls.log).containsExactly("open:$CHILD_PID", "wait:$CHILD_PID", "close:$DESCRIPTOR")
    }

    @Test
    fun `a claimed child uses its stable descriptor and confirms exact native reap`() {
        val calls = FakeCalls()
        calls.waitResults.addAll(listOf(LinuxNativeCall(0), LinuxNativeCall(CHILD_PID, status = KILL_SIGNAL)))

        checkNotNull(kernel(calls).claimChild(CHILD_PID)).use { it.terminateAndReap { REMAINING_NANOS } }

        assertThat(calls.log).containsExactly(
            "open:$CHILD_PID",
            "wait:$CHILD_PID",
            "signal:$DESCRIPTOR",
            "poll:$DESCRIPTOR",
            "wait:$CHILD_PID",
            "close:$DESCRIPTOR",
        )
    }

    @Nested
    inner class ClaimRaces {
        @Test
        fun `a concurrent reaper cannot replace the process pinned for signaling`() {
            val calls = FakeCalls()
            calls.waitResults.addAll(listOf(LinuxNativeCall(0), LinuxNativeCall(-1, NO_CHILDREN)))
            var processBehindPid = "owned child"
            var descriptorTarget: String? = null
            var signaledTarget: String? = null
            val reaperRace = object : LinuxProcessCalls by calls {
                override fun openPidfd(pid: Int): LinuxNativeCall {
                    descriptorTarget = processBehindPid
                    return calls.openPidfd(pid)
                }

                override fun waitChild(pid: Int): LinuxNativeCall = calls.waitChild(pid).also {
                    // A legitimate reaper wins immediately after the ownership result, before the caller's next call.
                    if (it.result == 0) processBehindPid = "unrelated replacement"
                }

                override fun signalPidfd(descriptor: Int): LinuxNativeCall {
                    signaledTarget = descriptorTarget
                    calls.signalPidfd(descriptor)
                    return if (descriptorTarget ==
                        "owned child"
                    ) {
                        LinuxNativeCall(-1, PROCESS_EXITED)
                    } else {
                        LinuxNativeCall(0)
                    }
                }
            }

            assertThatThrownBy {
                checkNotNull(LinuxNativeChildKernel(reaperRace) { emptyList() }.claimChild(CHILD_PID)).use {
                    it.terminateAndReap { REMAINING_NANOS }
                }
            }.isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("unconfirmed")

            assertThat(signaledTarget).isEqualTo("owned child")
        }

        @Test
        fun `a failed descriptor acquisition never validates a reusable numeric PID`() {
            val calls = FakeCalls().apply { openResult = LinuxNativeCall(-1, PROCESS_EXITED) }

            assertThatThrownBy { kernel(calls).claimChild(CHILD_PID) }
                .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("pidfd open")

            assertThat(calls.log).containsExactly("open:$CHILD_PID")
        }

        @Test
        fun `a rejected ownership claim preserves its failure when descriptor closure also fails`() {
            val calls = FakeCalls().apply { closeResult = LinuxNativeCall(-1, IO_ERROR) }
            calls.waitResults.add(LinuxNativeCall(-1, NO_CHILDREN))

            val failure = checkNotNull(catchThrowable { kernel(calls).claimChild(CHILD_PID) })

            assertThat(failure).isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("ownership")
            assertThat(failure.suppressed.single().message).contains("close")
            assertThat(calls.log).containsExactly("open:$CHILD_PID", "wait:$CHILD_PID", "close:$DESCRIPTOR")
        }
    }

    @Test
    fun `poll interruption and timeout retry only within the shared remaining budget`() {
        val calls = FakeCalls()
        calls.waitResults.addAll(listOf(LinuxNativeCall(0), LinuxNativeCall(CHILD_PID)))
        calls.pollResults.addAll(
            listOf(LinuxNativeCall(-1, INTERRUPTED), LinuxNativeCall(0), LinuxNativeCall(1, status = READABLE)),
        )
        val remaining = AtomicLong(REMAINING_NANOS)
        calls.onPoll = { remaining.addAndGet(-BUDGET_DECREMENT_NANOS) }

        checkNotNull(kernel(calls).claimChild(CHILD_PID)).use {
            it.terminateAndReap(remaining::get)
        }

        assertThat(calls.pollTimeouts).hasSize(POLL_ATTEMPTS)
        assertThat(calls.pollTimeouts.zipWithNext()).allMatch { (first, second) -> second < first }
    }

    @Test
    fun `an invalid pidfd poll is an unconfirmed failure and still closes the descriptor`() {
        val calls = FakeCalls()
        calls.pollResults.add(LinuxNativeCall(1, status = INVALID_DESCRIPTOR))

        assertThatThrownBy {
            checkNotNull(kernel(calls).claimChild(CHILD_PID)).use { it.terminateAndReap { REMAINING_NANOS } }
        }.isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("unconfirmed")

        assertThat(calls.log).contains("close:$DESCRIPTOR")
    }

    @Test
    fun `a child that already exited can still be confirmed through its descriptor`() {
        val calls = FakeCalls().apply { signalResult = LinuxNativeCall(-1, PROCESS_EXITED) }
        calls.waitResults.addAll(listOf(LinuxNativeCall(0), LinuxNativeCall(CHILD_PID)))

        checkNotNull(kernel(calls).claimChild(CHILD_PID)).use { it.terminateAndReap { REMAINING_NANOS } }

        assertThat(calls.log).contains("poll:$DESCRIPTOR", "wait:$CHILD_PID", "close:$DESCRIPTOR")
    }

    @Test
    fun `a child reaped during claim closes its descriptor without signaling`() {
        val calls = FakeCalls()
        calls.waitResults.add(LinuxNativeCall(CHILD_PID))

        assertThat(kernel(calls).claimChild(CHILD_PID)).isNull()

        assertThat(calls.log).containsExactly("open:$CHILD_PID", "wait:$CHILD_PID", "close:$DESCRIPTOR")
    }

    @Test
    fun `native descriptor close failure stays suppressed beneath the signal failure`() {
        val calls = FakeCalls().apply {
            signalResult = LinuxNativeCall(-1, PERMISSION_DENIED)
            closeResult = LinuxNativeCall(-1, IO_ERROR)
        }

        val failure = checkNotNull(
            catchThrowable {
                checkNotNull(kernel(calls).claimChild(CHILD_PID)).use { it.terminateAndReap { REMAINING_NANOS } }
            },
        )
        assertThat(failure).isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("signal")
        assertThat(failure.suppressed.single().message).contains("close")
    }

    @Test
    fun `an interrupted descriptor close is never retried against a potentially reused descriptor`() {
        val calls = FakeCalls().apply { closeResult = LinuxNativeCall(-1, INTERRUPTED) }
        val child = checkNotNull(kernel(calls).claimChild(CHILD_PID))

        assertThatThrownBy { child.close() }.isInstanceOf(LinuxOwnershipFailure::class.java)
        child.close()

        assertThat(calls.log.count { it == "close:$DESCRIPTOR" }).isEqualTo(1)
    }

    @Test
    fun `only ECHILD confirms that the supervisor owns no remaining children`() {
        val calls = FakeCalls()
        calls.waitResults.addAll(
            listOf(LinuxNativeCall(0), LinuxNativeCall(CHILD_PID), LinuxNativeCall(-1, NO_CHILDREN)),
        )
        val kernel = kernel(calls)

        assertThat(kernel.reapAnyExited()).isEqualTo(LinuxReapResult.Running)
        assertThat(kernel.reapAnyExited()).isEqualTo(LinuxReapResult.Reaped(CHILD_PID))
        assertThat(kernel.reapAnyExited()).isEqualTo(LinuxReapResult.Empty)
    }

    @Test
    fun `unexpected native wait failure does not become an empty ownership result`() {
        val calls = FakeCalls()
        calls.waitResults.add(LinuxNativeCall(-1, INVALID_ARGUMENT))

        assertThatThrownBy { kernel(calls).reapAnyExited() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("wait")
    }

    private fun kernel(calls: FakeCalls) = LinuxNativeChildKernel(calls) { emptyList() }

    private class FakeCalls : LinuxProcessCalls {
        override val currentPid = SUPERVISOR_PID
        val log = mutableListOf<String>()
        val waitResults = ArrayDeque<LinuxNativeCall>()
        val pollResults = ArrayDeque<LinuxNativeCall>()
        val pollTimeouts = mutableListOf<Int>()
        var armResult = LinuxNativeCall(0)
        var openResult = LinuxNativeCall(DESCRIPTOR)
        var signalResult = LinuxNativeCall(0)
        var closeResult = LinuxNativeCall(0)
        var onPoll: () -> Unit = {}

        override fun armSubreaper() = armResult.also { log.add("subreaper") }
        override fun openPidfd(pid: Int) = openResult.also { log.add("open:$pid") }
        override fun signalPidfd(descriptor: Int) = signalResult.also { log.add("signal:$descriptor") }
        override fun pollPidfd(descriptor: Int, timeoutMillis: Int): LinuxNativeCall {
            log.add("poll:$descriptor")
            pollTimeouts.add(timeoutMillis)
            onPoll()
            return pollResults.removeFirstOrNull() ?: LinuxNativeCall(1, status = READABLE)
        }
        override fun waitChild(pid: Int): LinuxNativeCall {
            log.add("wait:$pid")
            return waitResults.removeFirstOrNull()
                ?: if (pid == -1) LinuxNativeCall(-1, NO_CHILDREN) else LinuxNativeCall(0)
        }
        override fun closeDescriptor(descriptor: Int) = closeResult.also { log.add("close:$descriptor") }
    }

    private companion object {
        const val SUPERVISOR_PID = 10
        const val CHILD_PID = 101
        const val OTHER_PID = 102
        const val DESCRIPTOR = 7
        const val PERMISSION_DENIED = 1
        const val PROCESS_EXITED = 3
        const val INTERRUPTED = 4
        const val IO_ERROR = 5
        const val NO_CHILDREN = 10
        const val INVALID_ARGUMENT = 22
        const val READABLE = 1
        const val INVALID_DESCRIPTOR = 32
        const val KILL_SIGNAL = 9
        const val REMAINING_NANOS = 1_000_000_000L
        const val BUDGET_DECREMENT_NANOS = 200_000_000L
        const val POLL_ATTEMPTS = 3
    }
}
