package com.github.albertocavalcante.groovycommon.functional

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Composable pipeline interface for strategy-based processing.
 *
 * This is a generalized version of the pattern used in SymbolResolutionStrategy,
 * enabling reuse across different domains (completion, diagnostics, type inference, etc.)
 *
 * ## Design
 *
 * Each pipeline stage is a suspending function that:
 * - Returns `Either.Right(result)` on success → pipeline short-circuits
 * - Returns `Either.Left(error)` on failure → next stage is tried
 *
 * This enables Railway-Oriented Programming where we try strategies in order
 * until one succeeds.
 *
 * @param C Context type passed to each stage
 * @param R Result type on success
 */
fun interface Pipeline<C, R> {
    /**
     * Execute this pipeline stage with the given context.
     *
     * @param context Immutable context for this execution
     * @return Either.Right(result) on success, Either.Left(error) on failure
     */
    suspend fun execute(context: C): Either<DomainError, R>

    companion object {
        private val logger = LoggerFactory.getLogger(Pipeline::class.java)

        /**
         * Compose multiple pipelines into one that short-circuits on first success.
         *
         * Pipelines are tried in order (priority). First [Either.Right] wins,
         * remaining pipelines are skipped. If all fail, returns the last error.
         *
         * ## Example
         * ```kotlin
         * val combined = Pipeline.firstSuccess(
         *     CacheStrategy(),
         *     DatabaseStrategy(),
         *     FallbackStrategy(),
         * )
         * combined.execute(context).fold(
         *     ifLeft = { logger.warn("All strategies failed: ${it.reason}") },
         *     ifRight = { result -> process(result) }
         * )
         * ```
         *
         * @param pipelines Pipelines in priority order (first = highest priority)
         * @return A composite pipeline that tries each in sequence
         */
        @Suppress("TooGenericExceptionCaught")
        fun <C, R> firstSuccess(pipelines: List<Pipeline<C, R>>): Pipeline<C, R> = Pipeline { context ->
            var lastError: DomainError = DomainError("No pipelines provided", "pipeline")

            for (pipeline in pipelines) {
                try {
                    val result = pipeline.execute(context)
                    result.fold(
                        ifLeft = { lastError = it },
                        ifRight = { return@Pipeline it.right() },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Error) {
                    throw e
                } catch (e: Exception) {
                    // NOTE: Broad catch to keep pipeline resilient.
                    // TODO: Consider typed error handling for known failure modes.
                    logger.debug(
                        "Pipeline stage threw unexpectedly",
                        e,
                    )
                    lastError = DomainError(
                        reason = "Stage threw: ${e.message}",
                        source = pipeline::class.simpleName ?: "unknown",
                        cause = e,
                    )
                }
            }

            lastError.left()
        }

        /**
         * Vararg convenience for [firstSuccess].
         */
        fun <C, R> firstSuccess(vararg pipelines: Pipeline<C, R>): Pipeline<C, R> = firstSuccess(pipelines.asList())

        /**
         * Create a pipeline that always succeeds with the given value.
         */
        fun <C, R> constant(value: R): Pipeline<C, R> = Pipeline { value.right() }

        /**
         * Create a pipeline that always fails with the given error.
         */
        fun <C, R> fail(reason: String, source: String = "constant"): Pipeline<C, R> =
            Pipeline { DomainError(reason, source).left() }
    }
}
