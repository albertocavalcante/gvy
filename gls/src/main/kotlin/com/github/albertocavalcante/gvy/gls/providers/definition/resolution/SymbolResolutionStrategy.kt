// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.common.functional.DomainError
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionInputs
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.net.URI

/**
 * Type alias for resolution-specific errors.
 *
 * Uses [DomainError] from groovy-common for consistent error handling across modules.
 * The `source` field of [DomainError] is used to track which strategy produced the error.
 *
 * Using Arrow's Either<ResolutionError, DefinitionResult> provides:
 * - Industry-standard functional error handling
 * - Rich combinators: map, flatMap, fold, recover, getOrElse
 * - Short-circuit semantics in either { } DSL
 *
 * @see SymbolResolutionStrategy for usage in resolution pipelines
 */
typealias ResolutionError = DomainError

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
) {
    internal var inputs: DefinitionInputs? = null
        private set

    internal constructor(targetNode: ASTNode, documentUri: URI, position: Position, inputs: DefinitionInputs?) :
        this(targetNode, documentUri, position) {
        this.inputs = inputs
    }
}

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
 *     ifLeft = { error -> logger.debug { "Resolution failed: ${error.reason}" } },
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
        private val logger = KotlinLogging.logger {}

        /**
         * Compose multiple strategies into a pipeline that short-circuits on first success.
         *
         * Strategies are tried in order (priority). First [Either.Right] wins, remaining strategies are skipped.
         *
         * If none succeeds the result depends on whether any strategy threw: a strategy that threw is a failure
         * of the lookup, not a miss, so the FIRST such failure is returned (a [ResolutionError] whose `cause` is
         * the throwable and whose `source` is the strategy) and a later strategy's ordinary miss cannot hide it.
         * With no throw, the last strategy's miss is returned. A [ResolutionError] with a `cause` therefore always
         * means "a strategy threw"; strategies never set one for a miss.
         *
         * Cancellation and [Error]s propagate; they are never results.
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
                var firstThrow: ResolutionError? = null

                for (strategy in strategies) {
                    // Short-circuit: if we already have a Right (success), stop
                    if (result.isRight()) break

                    result = try {
                        strategy.resolve(context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Error) {
                        throw e
                    } catch (e: ResponseErrorException) {
                        if (context.inputs != null) throw e
                        val thrown = strategy.failure(e)
                        if (firstThrow == null) firstThrow = thrown
                        thrown.left()
                    } catch (e: Exception) {
                        val thrown = strategy.failure(e)
                        if (firstThrow == null) firstThrow = thrown
                        thrown.left()
                    }
                }

                if (result.isRight()) result else (firstThrow?.left() ?: result)
            }

        private fun SymbolResolutionStrategy.failure(error: Exception): ResolutionError {
            val name = this::class.simpleName ?: "unknown"
            logger.debug(error) { "Resolution strategy $name threw unexpectedly" }
            return ResolutionError("Strategy threw: ${error.message ?: error::class.simpleName}", name, error)
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
