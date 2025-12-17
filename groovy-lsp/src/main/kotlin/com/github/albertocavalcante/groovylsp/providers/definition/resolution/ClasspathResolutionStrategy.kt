package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolves class references to JAR/JRT classpath dependencies with source navigation.
 *
 * This strategy handles external dependencies:
 * - JDK classes (jrt: URIs) → extracts from $JAVA_HOME/lib/src.zip
 * - Maven dependencies (jar: URIs) → downloads source JAR
 *
 * **Priority: LOWEST** - only for external dependencies not in workspace.
 */
class ClasspathResolutionStrategy(
    private val compilationService: GroovyCompilationService,
    private val sourceNavigator: SourceNavigator?,
) : SymbolResolutionStrategy {

    private val logger = LoggerFactory.getLogger(ClasspathResolutionStrategy::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        val className = getClassName(context.targetNode)
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)

        val classpathUri = compilationService.findClasspathClass(className)
            ?: return SymbolResolutionStrategy.notFound("Class $className not on classpath", STRATEGY_NAME)

        logger.debug("Found classpath class {} at {}", className, classpathUri)

        sourceNavigator?.let { navigator ->
            navigateToSourceIfPossible(navigator, classpathUri, className)
                ?.let { return it }
        }

        return resolveBinaryFallback(classpathUri, className)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun navigateToSourceIfPossible(
        sourceNavigator: SourceNavigator,
        classpathUri: URI,
        className: String,
    ): ResolutionResult? = try {
        when (val result = sourceNavigator.navigateToSource(classpathUri, className)) {
            is SourceNavigator.SourceResult.SourceLocation -> {
                logger.debug("Found source for {} at {}", className, result.uri)
                val range = result.lineNumber?.let(::toZeroBasedLineRange)
                SymbolResolutionStrategy.found(
                    DefinitionResolver.DefinitionResult.Binary(result.uri, className, range),
                )
            }

            is SourceNavigator.SourceResult.BinaryOnly -> {
                logger.debug("No source available for {}: {}", className, result.reason)
                null
            }
        }
    } catch (e: CancellationException) {
        throw e // Preserve coroutine cancellation
    } catch (e: Exception) {
        // NOTE: Source navigation is best-effort; resolution should still succeed with binaries when possible.
        // TODO: Narrow the caught exception types once SourceNavigator exposes a more explicit error surface.
        logger.warn("Failed to navigate to source for {}: {}", className, e.message, e)
        null
    }

    private fun resolveBinaryFallback(classpathUri: URI, className: String): ResolutionResult {
        // Only return binary result for URIs that VS Code can actually open
        return when (classpathUri.scheme) {
            "file" -> SymbolResolutionStrategy.found(
                DefinitionResolver.DefinitionResult.Binary(classpathUri, className),
            )

            "jrt" -> {
                logger.debug("JDK source not available for {}", className)
                SymbolResolutionStrategy.notFound(
                    "JDK source not available (src.zip extraction failed)",
                    STRATEGY_NAME,
                )
            }

            "jar" -> {
                logger.debug("No source available for {} - jar: URI not openable", className)
                SymbolResolutionStrategy.notFound(
                    "No source JAR available for dependency",
                    STRATEGY_NAME,
                )
            }

            else -> {
                logger.debug("Unsupported URI scheme for {}: {}", className, classpathUri.scheme)
                SymbolResolutionStrategy.notFound(
                    "Unsupported URI scheme: ${classpathUri.scheme}",
                    STRATEGY_NAME,
                )
            }
        }
    }

    private fun toZeroBasedLineRange(oneBasedLine: Int): Range {
        val line0 = oneBasedLine - 1
        return Range(Position(line0, 0), Position(line0, 0))
    }

    companion object {
        private const val STRATEGY_NAME = "Classpath"
    }
}
