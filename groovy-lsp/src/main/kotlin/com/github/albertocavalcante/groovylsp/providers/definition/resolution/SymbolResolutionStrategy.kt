package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovyparser.ast.types.Position
import kotlinx.coroutines.CancellationException
import org.codehaus.groovy.ast.ASTNode
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Error type for symbol resolution failures.
 *
 * Using Arrow's Either<ResolutionError, DefinitionResult> provides:
 * - Industry-standard functional error handling
 * - Rich combinators: map, flatMap, fold, recover, getOrElse
 * - Short-circuit semantics in either { } DSL
 *
 * @see SymbolResolutionStrategy for usage in resolution pipelines
 */
data class ResolutionError(
    /** Human-readable description of why resolution failed */
    val reason: String,
    /** Strategy that produced this error (for debugging) */
    val strategy: String = "unknown",
)

/** Type alias for resolution results using Arrow Either */
typealias ResolutionResult = Either<ResolutionError, DefinitionResolver.DefinitionResult>

/**
 * Context for symbol resolution containing all necessary lookup data.
 *
 * Passed immutably through the resolution pipeline, allowing each strategy
 * to access the AST node and contextual information.
 */
data class ResolutionContext(
    /** The AST node at the cursor position to resolve */
    val targetNode: ASTNode,
    /** URI of the document containing the cursor */
    val documentUri: URI,
    /** Cursor position within the document */
    val position: Position,
)

/**
 * Functional interface for symbol resolution strategies.
 *
 * Each implementation is responsible for ONE specific resolution approach:
 * - [JenkinsVarsResolutionStrategy]: Jenkins vars/ directory lookup
 * - [LocalSymbolResolutionStrategy]: Local AST and symbol table
 * - [GlobalClassResolutionStrategy]: Cross-file class lookup via symbol index
 * - [ClasspathResolutionStrategy]: JAR/JRT classpath navigation
 *
 * Returns [Either.Left] with [ResolutionError] if resolution fails,
 * or [Either.Right] with [DefinitionResolver.DefinitionResult] on success.
 *
 * ## Example Pipeline
 * ```kotlin
 * val pipeline = SymbolResolutionStrategy.pipeline(
 *     JenkinsVarsResolutionStrategy(compilationService),
 *     LocalSymbolResolutionStrategy(astVisitor, symbolTable),
 *     GlobalClassResolutionStrategy(compilationService),
 *     ClasspathResolutionStrategy(compilationService, sourceNavigator),
 * )
 * pipeline.resolve(context).fold(
 *     ifLeft = { error -> logger.debug("Resolution failed: ${error.reason}") },
 *     ifRight = { result -> emit(result) }
 * )
 * ```
 */
fun interface SymbolResolutionStrategy {
    /**
     * Attempt to resolve the symbol in the given context.
     *
     * @param context Resolution context with target node and document info
     * @return Either.Right(result) if resolved, Either.Left(error) otherwise
     */
    suspend fun resolve(context: ResolutionContext): ResolutionResult

    companion object {
        private val logger = LoggerFactory.getLogger(SymbolResolutionStrategy::class.java)

        /**
         * Compose multiple strategies into a pipeline that short-circuits on first success.
         *
         * Strategies are tried in order (priority). First [Either.Right] wins,
         * remaining strategies are skipped. If all fail, returns the last error.
         *
         * Prefer passing a list/sequence to avoid vararg spread operator overhead.
         *
         * @param strategies Strategies in priority order (first = highest priority)
         * @return A composite strategy that tries each in sequence
         */
        @Suppress("TooGenericExceptionCaught")
        fun pipeline(strategies: Iterable<SymbolResolutionStrategy>): SymbolResolutionStrategy =
            SymbolResolutionStrategy { context ->
                var result: ResolutionResult = ResolutionError("No strategies in pipeline", "pipeline").left()

                for (strategy in strategies) {
                    // Short-circuit: if we already have a Right (success), stop
                    if (result.isRight()) break

                    result = try {
                        strategy.resolve(context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Error) {
                        throw e
                    } catch (e: Exception) {
                        // NOTE: This is intentionally a broad catch to keep definition resolution resilient.
                        // TODO: Replace with strategy-specific error handling (or typed ResolutionError codes) so we
                        // only catch known failure modes and let truly unexpected exceptions fail fast in tests.
                        logger.debug(
                            "Resolution strategy {} threw unexpectedly",
                            strategy::class.simpleName ?: "unknown",
                            e,
                        )
                        ResolutionError(
                            "Strategy threw: ${e.message}",
                            strategy::class.simpleName ?: "unknown",
                        ).left()
                    }
                }

                result
            }

        fun pipeline(vararg strategies: SymbolResolutionStrategy): SymbolResolutionStrategy =
            pipeline(strategies.asList())

        /** Convenience: error for strategies that don't apply to the node type */
        fun notApplicable(strategy: String = "unknown"): ResolutionResult =
            ResolutionError("Strategy not applicable to this node type", strategy).left()

        /** Convenience: wrap a successful result */
        fun found(result: DefinitionResolver.DefinitionResult): ResolutionResult = result.right()

        /** Convenience: wrap an error */
        fun notFound(reason: String, strategy: String = "unknown"): ResolutionResult =
            ResolutionError(reason, strategy).left()
    }
}
