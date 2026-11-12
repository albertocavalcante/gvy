package com.github.albertocavalcante.gvy.gls.e2e

internal data class LinuxNativeCall(val result: Int, val error: Int = 0, val status: Int = 0)

/** Raw Linux results carry errno immediately, before another native call can replace it. */
internal interface LinuxProcessCalls {
    val currentPid: Int
    fun armSubreaper(): LinuxNativeCall
    fun openPidfd(pid: Int): LinuxNativeCall
    fun signalPidfd(descriptor: Int): LinuxNativeCall
    fun pollPidfd(descriptor: Int, timeoutMillis: Int): LinuxNativeCall
    fun waitChild(pid: Int): LinuxNativeCall
    fun closeDescriptor(descriptor: Int): LinuxNativeCall
}

internal class LinuxNativeChildKernel(private val calls: LinuxProcessCalls, private val childIds: () -> List<Int>) :
    LinuxChildKernel {
    fun prepare() {
        calls.armSubreaper().expectZero("subreaper setup")
        val descriptor = calls.openPidfd(calls.currentPid).descriptorOrFail()
        AutoCloseable { calls.closeDescriptor(descriptor).expectZero("capability pidfd close") }.use { }
        if (ownedChildren().isNotEmpty() || reapAnyExited() != LinuxReapResult.Empty) {
            throw LinuxOwnershipFailure("Linux CLI ownership preflight found unexpected existing children")
        }
    }

    override fun ownedChildren(): List<Int> = childIds()

    override fun claimChild(pid: Int): LinuxOwnedChild? {
        if (pid <= 0) throw LinuxOwnershipFailure("Linux CLI ownership mismatch: invalid child PID")
        // Pin identity before ownership validation: another reaper can recycle the PID immediately after waitpid.
        val descriptor = calls.openPidfd(pid).descriptorOrFail()
        var transferred = false
        return AutoCloseable {
            if (!transferred) calls.closeDescriptor(descriptor).expectZero("unclaimed pidfd close")
        }.use {
            val waited = calls.waitChild(pid)
            when (waited.result) {
                pid -> null
                0 -> NativeOwnedChild(pid, descriptor, calls).also { transferred = true }
                else -> throw LinuxOwnershipFailure(
                    "Linux CLI ownership mismatch while claiming child $pid (errno=${waited.error})",
                )
            }
        }
    }

    override fun reapAnyExited(): LinuxReapResult {
        val waited = calls.waitChild(ANY_CHILD)
        return when {
            waited.result > 0 -> LinuxReapResult.Reaped(waited.result)
            waited.result == 0 -> LinuxReapResult.Running
            waited.result == -1 && waited.error == LINUX_ECHILD -> LinuxReapResult.Empty
            else -> throw unconfirmedNative("native child wait", waited.error)
        }
    }
}

private class NativeOwnedChild(
    private val pid: Int,
    private val descriptor: Int,
    private val calls: LinuxProcessCalls,
) : LinuxOwnedChild {
    private var closed = false

    override fun terminateAndReap(timeRemaining: () -> Long) {
        if (closed) throw LinuxOwnershipFailure("Linux CLI child cleanup unconfirmed: its pidfd is already closed")
        remainingMillis(timeRemaining)
        val signal = calls.signalPidfd(descriptor)
        if (signal.result != 0 && !(signal.result == -1 && signal.error == LINUX_ESRCH)) {
            throw unconfirmedNative("pidfd signal", signal.error)
        }
        awaitExit(timeRemaining)
        val reaped = calls.waitChild(pid)
        if (reaped.result != pid) {
            throw LinuxOwnershipFailure(
                "Linux CLI child cleanup unconfirmed: exact PID reap failed (errno=${reaped.error})",
            )
        }
    }

    private fun awaitExit(timeRemaining: () -> Long) {
        while (true) {
            val poll = calls.pollPidfd(descriptor, remainingMillis(timeRemaining))
            when {
                poll.result == 0 || poll.result == -1 && poll.error == LINUX_EINTR -> Unit
                poll.result == 1 && poll.status and LINUX_POLLIN != 0 && poll.status and LINUX_POLLNVAL == 0 -> return
                else -> throw unconfirmedNative("pidfd exit poll", poll.error)
            }
        }
    }

    override fun close() {
        if (closed) return
        // Linux may already have released the descriptor when close returns EINTR; retry could close a reused fd.
        closed = true
        calls.closeDescriptor(descriptor).expectZero("pidfd close")
    }
}

private fun remainingMillis(timeRemaining: () -> Long): Int {
    val remaining = timeRemaining()
    if (remaining <= 0) throw LinuxOwnershipFailure("Linux CLI child cleanup unconfirmed: the shared deadline expired")
    val milliseconds = remaining / NANOS_PER_MILLISECOND + if (remaining % NANOS_PER_MILLISECOND == 0L) 0 else 1
    return milliseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun LinuxNativeCall.expectZero(operation: String) {
    if (result != 0) throw unconfirmedNative(operation, error)
}

private fun LinuxNativeCall.descriptorOrFail(): Int {
    if (result < 0) throw unconfirmedNative("pidfd open", error)
    return result
}

private fun unconfirmedNative(operation: String, error: Int) =
    LinuxOwnershipFailure("Linux CLI descendant cleanup unconfirmed: $operation failed (errno=$error)")

private const val ANY_CHILD = -1
private const val LINUX_ECHILD = 10
private const val LINUX_EINTR = 4
private const val LINUX_ESRCH = 3
private const val LINUX_POLLIN = 1
private const val LINUX_POLLNVAL = 32
private const val NANOS_PER_MILLISECOND = 1_000_000L
