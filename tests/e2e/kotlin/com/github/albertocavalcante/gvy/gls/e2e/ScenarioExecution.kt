package com.github.albertocavalcante.gvy.gls.e2e

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** A harness deadline is an infrastructure failure, never an expected product failure (`xfail`). */
internal class ScenarioDeadlineExceeded(name: String, timeout: Duration) :
    IllegalStateException(
        "Scenario '$name' exceeded its whole-scenario deadline of ${timeout.toMillis()}ms",
    )

/** Runs one scenario on an owned worker so cancellation can interrupt blocking RPC, process and notification waits. */
internal class ScenarioExecution(
    private val timeout: Duration,
    private val cleanupTimeout: Duration = Duration.ofSeconds(SCENARIO_CLEANUP_TIMEOUT_SECONDS),
    private val threadFactory: ThreadFactory? = null,
    private val awaitOutcome: (CompletableFuture<Result<Unit>>, Long) -> Result<Unit> = { result, remaining ->
        result.get(remaining, TimeUnit.NANOSECONDS)
    },
    private val beforeAction: () -> Unit = {},
) {
    private val cancelled = AtomicBoolean()

    init {
        require(!timeout.isNegative && !timeout.isZero) { "Scenario timeout must be positive" }
        require(!cleanupTimeout.isNegative && !cleanupTimeout.isZero) { "Scenario cleanup timeout must be positive" }
    }

    // Validate conversion before submitting work: a huge authored timeout must not start an unbounded scenario.
    private val timeoutNanos = timeout.toNanos()
    private val cleanupTimeoutNanos = cleanupTimeout.toNanos()

    fun checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Scenario execution was cancelled")
        }
    }

    fun run(name: String, onExecutorClose: ((Throwable?) -> Unit)? = null, action: () -> Unit) {
        checkActive()
        val start = System.nanoTime()
        val executor =
            OwnedExecutor("groovy-lsp-e2e-scenario-$name", Duration.ofNanos(cleanupTimeoutNanos), threadFactory)
        val completion = CompletableFuture.runAsync({
            beforeAction()
            checkActive()
            action()
        }, executor.service)
        val outcome = completion.handle { _, failure ->
            if (failure == null) Result.success(Unit) else Result.failure(failure.completionCause())
        }
        AutoCloseable {
            AutoCloseable { if (cancelled.get()) reportCleanupFailure(completion) }.use {
                try {
                    executor.close()
                    onExecutorClose?.invoke(null)
                } catch (failure: Throwable) {
                    onExecutorClose?.invoke(failure)
                    throw failure
                }
            }
        }.use {
            val result = try {
                val remaining = (timeoutNanos - (System.nanoTime() - start)).coerceAtLeast(0)
                awaitOutcome(outcome, remaining)
            } catch (_: TimeoutException) {
                cancelled.set(true)
                // Interrupt the owned worker without cancelling completion, which retains cleanup failures.
                executor.cancel()
                throw ScenarioDeadlineExceeded(name, timeout)
            } catch (failure: InterruptedException) {
                cancelled.set(true)
                executor.cancel()
                Thread.currentThread().interrupt()
                throw failure
            }
            result.getOrThrow()
        }
    }

    private fun reportCleanupFailure(completion: CompletableFuture<*>) {
        if (!completion.isDone) return // Cancellation can win before the worker starts.
        val completed = completion.handle { _, failure -> failure }.join() ?: return
        // Interruption may have come from cleanup itself; retaining it avoids claiming that cleanup succeeded.
        throw completed.completionCause()
    }
}

private const val SCENARIO_CLEANUP_TIMEOUT_SECONDS = 30L

private fun Throwable.completionCause(): Throwable = if (this is CompletionException) cause ?: this else this

/** Access and close happen on the scenario worker; cancellation is checked again after a late acquisition. */
internal class ScenarioSession(
    private val execution: ScenarioExecution,
    private val start: () -> LanguageServerSession,
) : AutoCloseable {
    private var acquired: LanguageServerSession? = null

    fun get(): LanguageServerSession {
        execution.checkActive()
        val session = acquired ?: start().also { acquired = it }
        execution.checkActive()
        return session
    }

    override fun close() {
        acquired?.close()
    }
}
