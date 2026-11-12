// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns both logical executor termination and actual worker-thread exit under one cleanup budget. */
internal class OwnedExecutor(private val name: String, closeTimeout: Duration, threadFactory: ThreadFactory? = null) :
    AutoCloseable {
    private val closeTimeoutNanos = closeTimeout.toNanos().also {
        require(it > 0) { "Executor cleanup timeout must be positive" }
    }
    private val workers = ConcurrentLinkedQueue<Thread>()
    private val cancellationRequested = AtomicBoolean()
    private val factory = threadFactory ?: ThreadFactory { task ->
        Thread(task, name).apply { isDaemon = true }
    }
    val service: ExecutorService = Executors.newCachedThreadPool { task ->
        factory.newThread(task).also(workers::add)
    }

    fun cancel() {
        if (cancellationRequested.compareAndSet(false, true)) service.shutdownNow()
    }

    override fun close() {
        var interrupted = Thread.interrupted()
        val start = System.nanoTime()
        fun remainingNanos() = closeTimeoutNanos - (System.nanoTime() - start)
        try {
            // Repeat waits after cancellation without interrupting the worker's resource cleanup a second time.
            cancel()
            while (!service.isTerminated && remainingNanos() > 0) {
                try {
                    service.awaitTermination(remainingNanos().coerceAtLeast(0), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            joinWorkers(::remainingNanos) { interrupted = true }
            check(service.isTerminated && workers.none(Thread::isAlive)) {
                "Executor '$name' did not stop; resource cleanup is not confirmed"
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun joinWorkers(remainingNanos: () -> Long, onInterrupted: () -> Unit) {
        workers.forEach { worker ->
            while (worker.isAlive && remainingNanos() > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos().coerceAtLeast(0))
                } catch (_: InterruptedException) {
                    onInterrupted()
                }
            }
        }
    }
}
