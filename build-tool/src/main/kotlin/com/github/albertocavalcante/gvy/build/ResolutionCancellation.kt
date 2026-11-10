package com.github.albertocavalcante.gvy.build

import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cooperative cancellation for a blocking dependency resolution.
 *
 * The caller signals [cancel]; the build tool observes it between steps ([throwIfCancelled]), while it waits
 * ([awaitCancellation]), and inside blocking operations through [onCancel] (for example by cancelling a Gradle
 * Tooling API token). Nothing here interrupts threads, so pooled connections and file I/O are never interrupted
 * mid-operation.
 */
class ResolutionCancellation {
    private val cancelled = AtomicBoolean(false)
    private val signal = CountDownLatch(1)
    private val listeners = CopyOnWriteArrayList<Registration>()

    val isCancelled: Boolean get() = cancelled.get()

    /** Requests cancellation. Idempotent; runs each registered listener at most once. */
    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        signal.countDown()
        listeners.forEach { it.fire() }
    }

    /**
     * Runs [action] once when cancellation is requested, immediately if it already was. Close the returned handle
     * when the guarded operation ends so a later cancellation does not reach it.
     */
    fun onCancel(action: () -> Unit): AutoCloseable {
        val registration = Registration(action)
        listeners.add(registration)
        // A cancel() racing with add() may miss the new listener; fire it here. Registration runs at most once.
        if (isCancelled) registration.fire()
        return AutoCloseable {
            registration.close()
            listeners.remove(registration)
        }
    }

    /** Waits up to [timeoutMs] for cancellation; returns true if cancellation was requested. */
    fun awaitCancellation(timeoutMs: Long): Boolean = signal.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Throws [CancellationException] when cancellation was requested. */
    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("Dependency resolution cancelled")
    }

    private class Registration(private val action: () -> Unit) {
        private val done = AtomicBoolean(false)

        fun fire() {
            if (done.compareAndSet(false, true)) action()
        }

        fun close() {
            done.set(true)
        }
    }
}
