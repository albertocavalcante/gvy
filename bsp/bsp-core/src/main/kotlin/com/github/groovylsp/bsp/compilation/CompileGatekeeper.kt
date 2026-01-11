package com.github.groovylsp.bsp.compilation

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Prevents duplicate compilation requests using Bloop's deduplication pattern.
 *
 * When multiple clients request compilation of the same targets with identical
 * inputs (sources + classpath), only one compilation runs while others subscribe
 * to its events and await the same result.
 *
 * Thread-safe for concurrent access.
 */
object CompileGatekeeper {

    private val logger = KotlinLogging.logger {}
    private val runningCompilations = ConcurrentHashMap<UniqueCompileInputs, RunningCompilation>()

    /**
     * Unique identifier for a compilation request.
     *
     * Two compilations are considered identical if they target the same build
     * targets with the same source/classpath state (represented by hash).
     *
     * @property targetIds Set of build targets to compile
     * @property inputsHash Hash of sources + classpath for incremental detection
     */
    data class UniqueCompileInputs(val targetIds: Set<BuildTargetIdentifier>, val inputsHash: Long) {
        override fun toString(): String = "CompileInputs(targets=${targetIds.size}, hash=$inputsHash)"
    }

    /**
     * Active compilation state shared between subscribers.
     *
     * @property deferred Completes with the compilation result when done
     * @property observers Thread-safe list of callbacks invoked for each compilation event
     */
    data class RunningCompilation(
        val deferred: CompletableDeferred<CompileResult>,
        val observers: CopyOnWriteArrayList<(CompilationEvent) -> Unit>,
    )

    /**
     * Compiles with deduplication - joins existing compilation if in progress.
     *
     * If an identical compilation is already running:
     * 1. Subscribes [onEvent] to receive all compilation events
     * 2. Awaits the existing compilation's result
     * 3. Returns the shared result
     *
     * Otherwise:
     * 1. Starts a new compilation via [compile]
     * 2. Registers it in [runningCompilations]
     * 3. Broadcasts events to all observers
     * 4. Cleans up when complete
     *
     * @param inputs Unique compilation identifier
     * @param compile Suspending function that performs the actual compilation
     * @param onEvent Callback for compilation events (progress, diagnostics, etc.)
     * @return Compilation result (shared if deduplicated)
     */
    suspend fun compileWithDeduplication(
        inputs: UniqueCompileInputs,
        compile: suspend () -> CompileResult,
        onEvent: (CompilationEvent) -> Unit,
    ): CompileResult {
        // Atomically check-and-register to prevent race condition
        // computeIfAbsent ensures only one thread creates the RunningCompilation
        val running = runningCompilations.computeIfAbsent(inputs) {
            logger.debug { "Starting new compilation: $inputs" }
            val deferred = CompletableDeferred<CompileResult>()
            val observers = CopyOnWriteArrayList<(CompilationEvent) -> Unit>()
            observers.add(onEvent)
            RunningCompilation(deferred, observers)
        }

        // If we joined an existing compilation, add our observer
        if (!running.observers.contains(onEvent)) {
            logger.debug { "Joining existing compilation: $inputs" }
            running.observers.add(onEvent)
            return running.deferred.await()
        }

        // We created the compilation, so we run it
        return try {
            // Broadcast wrapper that notifies all observers
            val result = compile()
            running.deferred.complete(result)
            result
        } catch (e: Exception) {
            running.deferred.completeExceptionally(e)
            throw e
        } finally {
            // Clean up registration
            runningCompilations.remove(inputs)
            logger.debug { "Finished compilation: $inputs" }
        }
    }

    /**
     * Broadcasts a compilation event to all observers of the given inputs.
     *
     * Used by the compilation implementation to notify subscribers of
     * progress, diagnostics, etc. Safe to call even if no compilation
     * is registered (no-op in that case).
     *
     * @param inputs Compilation identifier
     * @param event Event to broadcast
     */
    fun broadcastEvent(inputs: UniqueCompileInputs, event: CompilationEvent) {
        val running = runningCompilations[inputs] ?: return
        // NOTE: CopyOnWriteArrayList allows safe concurrent iteration
        running.observers.forEach { observer ->
            try {
                observer(event)
            } catch (e: Exception) {
                logger.error(e) { "Observer failed to handle event: $event" }
            }
        }
    }

    /**
     * Returns current number of active compilations (for testing/monitoring).
     */
    fun activeCompilations(): Int = runningCompilations.size

    /**
     * Clears all registered compilations (for testing cleanup).
     *
     * WARNING: Only use in tests - calling this in production will break
     * waiting clients.
     */
    internal fun reset() {
        runningCompilations.clear()
    }
}
