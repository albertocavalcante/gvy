package com.github.albertocavalcante.gvy.gls.e2e

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** Owns the shared launcher executor, so cancelling listeners also retires their idle threads. */
internal class ListenerExecutor(
    scenarioName: String,
    threadFactory: ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "groovy-lsp-e2e-$scenarioName").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val workers = ConcurrentLinkedQueue<Thread>()
    val service: ExecutorService = Executors.newCachedThreadPool { task ->
        threadFactory.newThread(task).also(workers::add)
    }

    override fun close() {
        var interrupted = Thread.interrupted()
        val start = System.nanoTime()
        fun remainingNanos() = CLOSE_TIMEOUT.toNanos() - (System.nanoTime() - start)
        try {
            service.shutdownNow()
            while (!service.isTerminated && remainingNanos() > 0) {
                try {
                    service.awaitTermination(remainingNanos().coerceAtLeast(0), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            joinWorkers(::remainingNanos) { interrupted = true }
            check(service.isTerminated && workers.none(Thread::isAlive)) {
                "LSP listener executor or its owned threads did not stop"
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun joinWorkers(remainingNanos: () -> Long, onInterrupted: () -> Unit) {
        // ThreadPoolExecutor signals TERMINATED inside Worker.run, before its owning Thread has exited.
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

private const val CLOSE_TIMEOUT_SECONDS = 5L
private val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS)
