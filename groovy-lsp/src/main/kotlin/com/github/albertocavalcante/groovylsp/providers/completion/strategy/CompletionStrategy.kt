package com.github.albertocavalcante.groovylsp.providers.completion.strategy

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.gvy.common.functional.DomainError
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.eclipse.lsp4j.CompletionItem

/**
 * Type alias for completion-specific errors.
 * Uses DomainError from groovy-common for consistent error handling.
 */
typealias CompletionError = DomainError

/** Type alias for completion results using Arrow Either */
typealias CompletionResult = Either<CompletionError, List<CompletionItem>>

/**
 * Strategy interface for mode-specific completion providers.
 *
 * Each implementation is responsible for ONE specific completion approach:
 * - [JenkinsCompletionStrategy]: Jenkins steps, global variables, declarative
 * - [GroovyCompletionStrategy]: Core Groovy symbols, keywords, snippets
 *
 * Returns [Either.Left] with [CompletionError] if strategy doesn't apply,
 * or [Either.Right] with completion items on success.
 *
 * ## Example Usage
 * ```kotlin
 * val strategies = listOf(
 *     JenkinsCompletionStrategy(jenkinsCapabilities),
 *     GroovyCompletionStrategy(),
 * )
 * val aggregated = CompletionStrategy.aggregate(strategies)
 * val items = aggregated.complete(context).getOrElse { emptyList() }
 * ```
 */
internal fun interface CompletionStrategy {
    /**
     * Attempt to provide completions for the given context.
     *
     * @param context The completion context with AST, position, and mode info
     * @return Either.Right(items) if completions provided, Either.Left(error) if not applicable
     */
    suspend fun complete(context: CompletionStrategyContext): CompletionResult

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Compose multiple strategies, collecting all successful completions.
         *
         * Unlike definition resolution (short-circuit on first success),
         * completion aggregates results from all applicable strategies.
         *
         * @param strategies Strategies to aggregate
         * @return A composite strategy that collects all completions
         */
        @Suppress("TooGenericExceptionCaught")
        fun aggregate(strategies: List<CompletionStrategy>): CompletionStrategy = CompletionStrategy { context ->
            val allItems = mutableListOf<CompletionItem>()

            for (strategy in strategies) {
                val result = try {
                    strategy.complete(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Error) {
                    throw e
                } catch (e: Exception) {
                    logger.debug(e) {
                        "Completion strategy ${strategy::class.simpleName ?: "unknown"} threw unexpectedly"
                    }
                    notApplicable(strategy::class.simpleName ?: "unknown")
                }

                result.fold(
                    ifLeft = { /* skip non-applicable strategies */ },
                    ifRight = { items -> allItems.addAll(items) },
                )
            }

            allItems.right()
        }

        /** Convenience: strategy doesn't apply to this context */
        fun notApplicable(strategy: String = "unknown"): CompletionResult =
            CompletionError("Strategy not applicable", strategy).left()

        /** Convenience: wrap successful completions */
        fun found(items: List<CompletionItem>): CompletionResult = items.right()

        /** Convenience: wrap an error */
        fun error(reason: String, strategy: String = "unknown"): CompletionResult =
            CompletionError(reason, strategy).left()
    }
}
