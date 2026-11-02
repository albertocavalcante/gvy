package com.github.groovylsp.bsp.compilation

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Prevents duplicate compilation requests using Bloop's deduplication pattern.
 *
 * When multiple clients request compilation of the same targets with identical
 * inputs (sources + classpath), only one compilation runs while others subscribe
 * to its events and await the same result. A subscriber that joins late first
 * receives every event broadcast so far, so it sees the same task start/finish
 * pairs as the client that started the compilation.
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
     * Events kept per running compilation for late subscribers. A compilation that
     * broadcasts more stops recording (and logs it); late subscribers then miss the
     * later events, as they all did before replay existed.
     */
    internal const val MAX_REPLAYED_EVENTS = 10_000

    /**
     * Active compilation state shared between subscribers.
     *
     * [history] and [observers] change only under [lock], so a subscriber's replay
     * and its registration happen at one point in the event stream: it receives
     * every event exactly once, in broadcast order.
     *
     * @property deferred Completes with the compilation result when done
     */
    class RunningCompilation(val deferred: CompletableDeferred<CompileResult>) {
        private val lock = Any()
        private val history = ArrayList<CompilationEvent>()
        private val observers = ArrayList<(CompilationEvent) -> Unit>()
        private var historyFull = false

        /** Replays every event so far to [observer], then registers it for the rest. */
        fun subscribe(observer: (CompilationEvent) -> Unit) = synchronized(lock) {
            history.forEach { deliver(observer, it) }
            observers += observer
        }

        fun broadcast(event: CompilationEvent) = synchronized(lock) {
            if (history.size < MAX_REPLAYED_EVENTS) {
                history += event
            } else if (!historyFull) {
                historyFull = true
                logger.warn { "Over $MAX_REPLAYED_EVENTS events; late subscribers miss the rest" }
            }
            observers.forEach { deliver(it, event) }
        }

        private fun deliver(observer: (CompilationEvent) -> Unit, event: CompilationEvent) {
            @Suppress("TooGenericExceptionCaught") // Observers can fail in unpredictable ways, must not crash broadcast
            try {
                observer(event)
            } catch (e: Exception) {
                logger.error(e) { "Observer failed to handle event: $event" }
            }
        }
    }

    /**
     * Compiles with deduplication - joins existing compilation if in progress.
     *
     * If an identical compilation is already running:
     * 1. Subscribes [onEvent], replaying the events broadcast so far
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
        var created = false
        val running = runningCompilations.computeIfAbsent(inputs) {
            logger.debug { "Starting new compilation: $inputs" }
            created = true
            RunningCompilation(CompletableDeferred())
        }
        running.subscribe(onEvent)

        if (!created) {
            logger.debug { "Joining existing compilation: $inputs" }
            return running.deferred.await()
        }

        // We created the compilation, so we run it
        @Suppress("TooGenericExceptionCaught") // Compilation failures
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
     * progress, diagnostics, etc. The event is also kept for subscribers that
     * join later. Safe to call even if no compilation is registered (no-op in
     * that case).
     *
     * @param inputs Compilation identifier
     * @param event Event to broadcast
     */
    fun broadcastEvent(inputs: UniqueCompileInputs, event: CompilationEvent) {
        runningCompilations[inputs]?.broadcast(event)
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
