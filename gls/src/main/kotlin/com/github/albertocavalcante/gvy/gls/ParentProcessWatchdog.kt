package com.github.albertocavalcante.gvy.gls

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture

/**
 * Source of "this process has exited" signals, abstracted so the watchdog can be tested
 * without real processes.
 */
fun interface ProcessLiveness {
    /**
     * Returns a future that completes when the process [pid] exits, or null when the process
     * cannot be observed (unknown pid, different PID namespace, no permission).
     */
    fun exitFuture(pid: Long): CompletableFuture<*>?
}

/**
 * [ProcessLiveness] backed by [ProcessHandle]; `onExit()` also works for non-child processes.
 * Some clients send the server's own pid (an in-process client, or a test harness); that process
 * cannot be watched (`onExit()` throws for the current process) and is reported unobservable.
 */
object SystemProcessLiveness : ProcessLiveness {
    private val logger = KotlinLogging.logger {}

    override fun exitFuture(pid: Long): CompletableFuture<*>? {
        if (pid == ProcessHandle.current().pid()) return null
        return try {
            ProcessHandle.of(pid).map { it.onExit() }.orElse(null)
        } catch (e: IllegalStateException) {
            logger.debug(e) { "Cannot watch process $pid" }
            null
        } catch (e: SecurityException) {
            logger.debug(e) { "Not allowed to watch process $pid" }
            null
        }
    }
}

/**
 * Terminates the server when the editor (the process named by `InitializeParams.processId`)
 * dies, so the JVM is not orphaned.
 */
class ParentProcessWatchdog(
    private val liveness: ProcessLiveness = SystemProcessLiveness,
    private val onParentExit: () -> Unit,
) {
    private val logger = KotlinLogging.logger {}

    /** Starts watching [pid]. A null pid or an unobservable process is a no-op. */
    fun watch(pid: Int?) {
        if (pid == null) return
        val future = liveness.exitFuture(pid.toLong())
        if (future == null) {
            logger.warn { "Parent process $pid is not observable; parent watchdog disabled" }
            return
        }
        logger.info { "Watching parent process $pid" }
        future.whenComplete { _, _ ->
            logger.warn { "Parent process $pid exited; shutting down language server" }
            onParentExit()
        }
    }
}
