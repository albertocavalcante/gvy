package com.github.groovylsp.bsp.compilation

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileResult
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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

    private val logger = LoggerFactory.getLogger(CompileGatekeeper::class.java)
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
     * @property observers Callbacks invoked for each compilation event
     */
    data class RunningCompilation(
        val deferred: CompletableDeferred<CompileResult>,
        val observers: MutableList<(CompilationEvent) -> Unit>,
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
        // Try to join existing compilation
        val existing = runningCompilations[inputs]
        if (existing != null) {
            logger.debug("Joining existing compilation: $inputs")
            existing.observers.add(onEvent)
            return existing.deferred.await()
        }

        // Start new compilation
        logger.debug("Starting new compilation: $inputs")
        val deferred = CompletableDeferred<CompileResult>()
        val observers = mutableListOf(onEvent)
        val running = RunningCompilation(deferred, observers)

        // Register before starting to catch early joiners
        runningCompilations[inputs] = running

        return try {
            // Broadcast wrapper that notifies all observers
            val result = compile()
            deferred.complete(result)
            result
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            // Clean up registration
            runningCompilations.remove(inputs)
            logger.debug("Finished compilation: $inputs")
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
        // NOTE: Using toList() to avoid ConcurrentModificationException
        //   if an observer modifies the list during iteration
        running.observers.toList().forEach { observer ->
            try {
                observer(event)
            } catch (e: Exception) {
                logger.error("Observer failed to handle event: $event", e)
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
