package com.github.albertocavalcante.gvy.gls.e2e

import java.io.IOException
import java.time.Duration

/** Only the dedicated supervisor uses this kernel boundary; the test JVM never adopts payload children. */
internal interface LinuxChildKernel {
    fun ownedChildren(): List<Int>
    fun claimChild(pid: Int): LinuxOwnedChild?
    fun reapAnyExited(): LinuxReapResult
}

internal interface LinuxOwnedChild : AutoCloseable {
    fun terminateAndReap(timeRemaining: () -> Long)
}

internal sealed interface LinuxReapResult {
    data object Empty : LinuxReapResult
    data object Running : LinuxReapResult
    data class Reaped(val pid: Int) : LinuxReapResult
}

internal class LinuxOwnershipFailure(message: String, cause: Exception? = null) : IOException(message, cause)

internal class LinuxChildOwnership(
    private val kernel: LinuxChildKernel,
    private val timeout: Duration = Duration.ofSeconds(OWNED_CHILD_CLEANUP_SECONDS),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val timeoutNanos = timeout.toNanos().also { require(it > 0) { "Cleanup timeout must be positive" } }

    fun release() {
        val started = nanoTime()
        release { timeoutNanos - (nanoTime() - started) }
    }

    /** Shares the caller's existing budget, including any direct-payload termination time. */
    fun release(timeRemaining: () -> Long) = LinuxChildDrain(kernel, timeRemaining).release()
}

private class LinuxChildDrain(private val kernel: LinuxChildKernel, private val timeRemaining: () -> Long) {
    private val failedPids = mutableSetOf<Int>()
    private var firstFailure: LinuxOwnershipFailure? = null

    fun release() {
        while (true) {
            remaining()
            val progressed = releaseChildren(observe(kernel::ownedChildren))
            remaining()
            when (observe(kernel::reapAnyExited)) {
                LinuxReapResult.Empty -> {
                    throwIfFailed()
                    return
                }
                is LinuxReapResult.Reaped -> continue
                LinuxReapResult.Running -> if (!progressed) {
                    throwIfFailed()
                    unconfirmed("the kernel still owns children that could not be released")
                }
            }
        }
    }

    private fun throwIfFailed() {
        firstFailure?.let { throw it }
    }

    private fun releaseChildren(children: List<Int>): Boolean {
        var progressed = false
        for (pid in children.distinct()) {
            if (pid !in failedPids) progressed = releaseChild(pid) || progressed
        }
        return progressed
    }

    private fun releaseChild(pid: Int): Boolean {
        remaining()
        return try {
            kernel.claimChild(pid)?.use { it.terminateAndReap(::remaining) }
            true
        } catch (failure: LinuxOwnershipFailure) {
            failedPids.add(pid)
            recordFailure(failure)
            false
        }
    }

    private fun recordFailure(failure: LinuxOwnershipFailure) {
        val first = firstFailure
        if (first == null) {
            firstFailure = failure
        } else if (first !== failure && first.suppressed.size < MAX_SUPPRESSED_FAILURES) {
            first.addSuppressed(failure)
        }
    }

    private fun unconfirmed(reason: String): Nothing {
        recordFailure(LinuxOwnershipFailure("Linux CLI descendant cleanup unconfirmed: $reason"))
        throw checkNotNull(firstFailure)
    }

    private fun remaining(): Long = timeRemaining().also {
        if (it <= 0) unconfirmed("the shared cleanup deadline expired")
    }

    private fun <T> observe(action: () -> T): T = try {
        action()
    } catch (failure: LinuxOwnershipFailure) {
        recordFailure(failure)
        throw checkNotNull(firstFailure)
    }
}

private const val OWNED_CHILD_CLEANUP_SECONDS = 5L
private const val MAX_SUPPRESSED_FAILURES = 16
