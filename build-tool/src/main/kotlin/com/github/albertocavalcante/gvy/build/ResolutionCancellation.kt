package com.github.albertocavalcante.gvy.build

import io.github.oshai.kotlinlogging.KotlinLogging
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
 *
 * A cancelled resolution ends with [CancellationException], which coroutines treat as their own cancellation. So
 * call [cancel] only when the resolution's result is no longer wanted by the code awaiting it: when the job running
 * the resolution is cancelled, or when that code is shutting down. A caller that cancels for another reason and
 * keeps running must catch the exception at the `resolve` call; otherwise the coroutine ends silently, as cancelled.
 */
class ResolutionCancellation {
    private val cancelled = AtomicBoolean(false)
    private val signal = CountDownLatch(1)
    private val listeners = CopyOnWriteArrayList<Registration>()

    val isCancelled: Boolean get() = cancelled.get()

    /** Registrations still held, for tests. */
    internal val registeredListeners: Int get() = listeners.size

    /**
     * Requests cancellation. Idempotent; runs each registered listener at most once, then releases every
     * registration. A listener that throws is logged and skipped, so it neither stops the others nor reaches the
     * caller.
     */
    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        signal.countDown()
        listeners.forEach { it.fire() }
        // Nothing runs a registration after this; one added meanwhile is fired and removed by onCancel.
        listeners.clear()
    }

    /**
     * Runs [action] once when cancellation is requested, immediately if it already was. Close the returned handle
     * when the guarded operation ends so a later cancellation does not reach it.
     */
    fun onCancel(action: () -> Unit): AutoCloseable {
        val registration = Registration(action)
        listeners.add(registration)
        // A cancel() racing with add() may miss the new listener; fire it here. Registration runs at most once,
        // and fire() never throws, so the registration is always removed.
        if (isCancelled) {
            registration.fire()
            listeners.remove(registration)
        }
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

    private companion object {
        val logger = KotlinLogging.logger {}
    }

    private class Registration(private val action: () -> Unit) {
        private val done = AtomicBoolean(false)

        @Suppress("TooGenericExceptionCaught") // one failing listener must not skip the others or fail cancel()
        fun fire() {
            if (!done.compareAndSet(false, true)) return
            try {
                action()
            } catch (e: Exception) {
                logger.warn(e) { "A cancellation listener failed; continuing" }
            }
        }

        fun close() {
            done.set(true)
        }
    }
}
