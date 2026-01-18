package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Ensures all open documents are compiled and indexed.
 *
 * This class encapsulates the logic for ensuring all open documents are compiled,
 * which is critical for cross-file features (definition, references, implementation)
 * that depend on the symbol index containing all relevant files.
 *
 * Fixes #749: Race condition where cross-file resolution fails when files are opened
 * via didOpen and definition request arrives before all files finish compiling.
 *
 * SAFEGUARDS (to prevent infinite loops and timeouts):
 * - maxIterations: Limits loop iterations
 * - maxTimeMs: Overall timeout for the entire process
 * - MAX_JOB_WAIT_TIMEOUT_MS: Timeout for waiting on diagnostic jobs
 * - ensureActive(): Checks for coroutine cancellation
 * - Exception handling: Catches compilation failures to avoid retry loops
 */
class CompilationEnsurer(
    private val documentProvider: DocumentProvider,
    private val compilationService: GroovyCompilationService,
    private val diagnosticJobs: ConcurrentHashMap<URI, Job>,
    private val maxTimeMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxIterations: Int = DEFAULT_ITERATIONS,
) {
    private val logger = KotlinLogging.logger {}
    private val failedUris = mutableSetOf<URI>()

    suspend fun ensureAllCompiled() {
        val startTime = System.currentTimeMillis()

        // Initial check for pending jobs and compilation status
        if (!hasWorkToDo()) return

        var iterations = 0
        var compiledAny = true

        while (compiledAny) {
            if (!checkSafeguards(iterations++, System.currentTimeMillis() - startTime)) {
                return
            }

            // Wait for pending diagnostic jobs
            val currentJob = currentCoroutineContext()[Job]
            val pendingJobs = diagnosticJobs.values.toList().filter { it != currentJob }
            if (pendingJobs.isNotEmpty()) {
                waitForPendingJobs(pendingJobs, iterations, System.currentTimeMillis() - startTime)
            }

            // Compile unindexed URIs
            compiledAny = compileUnindexedUris(startTime, iterations) ?: return
        }

        val completionElapsedMs = System.currentTimeMillis() - startTime
        logger.debug {
            "ensureAllOpenDocumentsCompiled: Completed after $iterations iterations " +
                "(${completionElapsedMs}ms)"
        }
    }

    private suspend fun hasWorkToDo(): Boolean {
        // PERFORMANCE OPTIMIZATION: Check if all documents are already compiled
        // This avoids unnecessary blocking when all documents are already compiled
        val currentJob = currentCoroutineContext()[Job]
        val hasPendingJobs = diagnosticJobs.values.any { it != currentJob && it.isActive }

        // Check if all open documents are already compiled
        val allDocumentsCompiled = try {
            documentProvider.getAllUris().all { uri ->
                compilationService.getSymbolStorage(uri) != null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug(e) { "ensureAllOpenDocumentsCompiled: Error checking compilation status" }
            false
        }

        if (!hasPendingJobs && allDocumentsCompiled) {
            logger.debug { "ensureAllOpenDocumentsCompiled: No pending jobs and all documents compiled, skipping" }
            return false
        }

        return true
    }

    private suspend fun checkSafeguards(iterations: Int, elapsedMs: Long): Boolean {
        // SAFEGUARD 1: Check coroutine cancellation
        currentCoroutineContext().ensureActive()

        // SAFEGUARD 2: Check iteration limit to prevent infinite loops
        if (iterations >= maxIterations) {
            logger.warn {
                "ensureAllOpenDocumentsCompiled: Reached max iterations ($maxIterations). " +
                    "Some documents may not be fully compiled. This may indicate a compilation loop or " +
                    "excessive file churn."
            }
            return false
        }

        // SAFEGUARD 3: Check overall timeout to prevent indefinite blocking
        if (elapsedMs > maxTimeMs) {
            logger.warn {
                "ensureAllOpenDocumentsCompiled: Timeout after ${elapsedMs}ms " +
                    "(limit: ${maxTimeMs}ms). " +
                    "Some documents may not be fully compiled."
            }
            return false
        }

        return true
    }

    private suspend fun waitForPendingJobs(pendingJobs: List<Job>, iterations: Int, elapsedMs: Long) {
        logger.debug {
            "ensureAllOpenDocumentsCompiled: Iteration $iterations - " +
                "Waiting for ${pendingJobs.size} pending compilation jobs " +
                "(elapsed: ${elapsedMs}ms)"
        }

        // SAFEGUARD 4: Timeout on joinAll to prevent indefinite blocking
        // Cap wait time by remaining overall timeout to avoid overshooting the hard limit
        val remainingMs = (maxTimeMs - elapsedMs).coerceAtLeast(0)
        val waitMs = minOf(MAX_JOB_WAIT_TIMEOUT_MS, remainingMs)
        val joinResult = if (waitMs > 0) {
            withTimeoutOrNull(waitMs) {
                pendingJobs.joinAll()
            }
        } else {
            null
        }

        if (joinResult == null) {
            logger.warn {
                "ensureAllOpenDocumentsCompiled: Timeout waiting for diagnostic jobs " +
                    "after ${waitMs}ms. Proceeding anyway."
            }
        }
    }

    @Suppress("NestedBlockDepth") // Complexity inherited from original implementation
    private suspend fun compileUnindexedUris(startTime: Long, iterations: Int): Boolean? {
        // Take a snapshot to avoid concurrent modification issues
        val urisSnapshot = try {
            documentProvider.getAllUris().toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "ensureAllOpenDocumentsCompiled: Error getting URIs snapshot" }
            return false
        }

        logger.debug {
            "ensureAllOpenDocumentsCompiled: Iteration $iterations - Processing ${urisSnapshot.size} open documents"
        }

        var compiledAny = false

        for (uri in urisSnapshot) {
            // SAFEGUARD 5: Check timeout inside loop to prevent long-running iterations
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > maxTimeMs) {
                logger.warn {
                    "ensureAllOpenDocumentsCompiled: Timeout during compilation loop after ${elapsed}ms. " +
                        "Stopping mid-iteration."
                }
                return null // Signal early termination due to timeout
            }

            // SAFEGUARD 6: Check cancellation in loop
            currentCoroutineContext().ensureActive()

            // Skip URIs that failed in previous iterations
            if (uri in failedUris) {
                continue
            }

            if (compilationService.getSymbolStorage(uri) == null) {
                val content = documentProvider.get(uri)
                if (content != null) {
                    logger.debug { "ensureAllOpenDocumentsCompiled: Compiling unindexed document: $uri" }

                    // SAFEGUARD 7: Catch exceptions to prevent retry loops
                    try {
                        compilationService.compile(uri, content)
                        compiledAny = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Log but continue - don't let one bad file break everything
                        // Track failed URIs to skip them in subsequent iterations
                        logger.error(e) {
                            "ensureAllOpenDocumentsCompiled: Failed to compile $uri. " +
                                "Skipping in subsequent iterations."
                        }
                        failedUris.add(uri)
                        // Do not set compiledAny here; we only mark successful compilations
                    }
                }
            }
        }

        return compiledAny
    }

    companion object {
        /**
         * Maximum iterations for ensureAllOpenDocumentsCompiled loop.
         *
         * This is a defensive bound to avoid looping indefinitely when compilation
         * continuously fails or new files keep being added. Note that the overall
         * duration of ensureAllOpenDocumentsCompiled is still capped by
         * [DEFAULT_TIMEOUT_MS], so increasing this value does not allow the
         * method to run longer than that hard timeout.
         */
        const val DEFAULT_ITERATIONS = 10

        /**
         * Maximum time (milliseconds) to spend in ensureAllOpenDocumentsCompiled.
         *
         * This value acts as the hard upper bound for the operation. Even though
         * the combination of [DEFAULT_ITERATIONS] and [MAX_JOB_WAIT_TIMEOUT_MS]
         * could theoretically suggest a longer duration (e.g. 10 iterations × 10 seconds
         * per joinAll = 100+ seconds), the use of this timeout (typically via
         * withTimeoutOrNull) ensures the actual worst-case latency is limited to
         * approximately this value (30 seconds).
         */
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /**
         * Maximum time (milliseconds) to wait for diagnostic jobs to complete.
         *
         * This bounds each joinAll() call so that slow or stuck jobs do not block
         * indefinitely. The overall operation is still additionally constrained by
         * [DEFAULT_TIMEOUT_MS], which defines the true worst-case latency.
         */
        private const val MAX_JOB_WAIT_TIMEOUT_MS = 10_000L
    }
}
