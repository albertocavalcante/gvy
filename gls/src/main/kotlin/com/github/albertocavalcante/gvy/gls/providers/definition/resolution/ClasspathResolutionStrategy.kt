package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ImportNode
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
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

    private val logger = KotlinLogging.logger {}

    @Suppress("TooGenericExceptionCaught")
    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        val importNode = context.targetNode as? ImportNode
        val className = getClassName(context.targetNode)
            ?: run {
                if (importNode != null) {
                    logger.debug {
                        "Classpath resolution skipped for import with no class name " +
                            "(static=${importNode.isStatic}, field=${importNode.fieldName}, text=${importNode.text})"
                    }
                }
                return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
            }

        val classpathUri = compilationService.findClasspathClass(className)
            ?: run {
                if (importNode != null) {
                    logger.debug {
                        "Classpath lookup failed for import $className " +
                            "(static=${importNode.isStatic}, field=${importNode.fieldName}, deps=${compilationService.workspaceManager.getDependencyClasspath().size})"
                    }
                }
                return SymbolResolutionStrategy.notFound("Class $className not on classpath", STRATEGY_NAME)
            }

        logger.debug { "Found classpath class $className at $classpathUri" }

        if (importNode != null && importNode.isStatic && importNode.fieldName != null) {
            sourceNavigator?.let { navigator ->
                navigateToStaticImportSource(navigator, classpathUri, className, importNode.fieldName)
                    ?.let { return it }
            }
        }

        sourceNavigator?.let { navigator ->
            navigateToSourceIfPossible(navigator, classpathUri, className)
                ?.let { return it }
        }

        return resolveBinaryFallback(classpathUri, className)
    }

    private suspend fun navigateToSourceIfPossible(
        sourceNavigator: SourceNavigator,
        classpathUri: URI,
        className: String,
    ): ResolutionResult? = withSourceNavigation(className) {
        sourceNavigator.navigateToSource(classpathUri, className)
    }

    private fun resolveBinaryFallback(classpathUri: URI, className: String): ResolutionResult {
        // Only return binary result for URIs that VS Code can actually open
        return when (classpathUri.scheme) {
            SCHEME_FILE -> SymbolResolutionStrategy.found(
                DefinitionResolver.DefinitionResult.Binary(classpathUri, className),
            )

            SCHEME_JRT -> {
                logger.debug { "JDK source not available for $className" }
                SymbolResolutionStrategy.notFound(
                    "JDK source not available (src.zip extraction failed)",
                    STRATEGY_NAME,
                )
            }

            SCHEME_JAR -> {
                logger.debug { "No source available for $className - jar: URI not openable" }
                SymbolResolutionStrategy.notFound(
                    "No source JAR available for dependency",
                    STRATEGY_NAME,
                )
            }

            else -> {
                logger.debug { "Unsupported URI scheme for $className: ${classpathUri.scheme}" }
                SymbolResolutionStrategy.notFound(
                    "Unsupported URI scheme: ${classpathUri.scheme}",
                    STRATEGY_NAME,
                )
            }
        }
    }

    private suspend fun navigateToStaticImportSource(
        sourceNavigator: SourceNavigator,
        classpathUri: URI,
        className: String,
        memberName: String,
    ): ResolutionResult? = withSourceNavigation("$className.$memberName") {
        sourceNavigator.navigateToMethodSource(classpathUri, className, memberName)
    }

    /**
     * Execute a source navigation operation with proper error handling.
     *
     * This higher-order function handles the common pattern of:
     * - Calling a SourceNavigator method
     * - Converting SourceLocation to ResolutionResult
     * - Returning null for BinaryOnly
     * - Properly handling CancellationException and other exceptions
     *
     * @param symbolName The name to use for logging (e.g., "com.example.Foo" or "com.example.Foo.method")
     * @param navigate The suspend function that performs the actual navigation
     * @return ResolutionResult if source was found, null otherwise
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun withSourceNavigation(
        symbolName: String,
        crossinline navigate: suspend () -> SourceNavigator.SourceResult,
    ): ResolutionResult? = try {
        when (val result = navigate()) {
            is SourceNavigator.SourceResult.SourceLocation -> {
                logger.debug { "Found source for $symbolName at ${result.uri}" }
                val range = result.lineNumber?.let(::toZeroBasedLineRange)
                SymbolResolutionStrategy.found(
                    DefinitionResolver.DefinitionResult.Binary(result.uri, symbolName, range),
                )
            }
            is SourceNavigator.SourceResult.BinaryOnly -> {
                logger.debug { "No source available for $symbolName: ${result.reason}" }
                null
            }
        }
    } catch (e: CancellationException) {
        throw e // Preserve coroutine cancellation
    } catch (e: Exception) {
        // NOTE: Source navigation is best-effort; resolution should still succeed with binaries when possible.
        logger.warn(e) { "Failed to navigate to source for $symbolName: ${e.message}" }
        null
    }

    private fun toZeroBasedLineRange(oneBasedLine: Int): Range {
        val line0 = oneBasedLine - 1
        return Range(Position(line0, 0), Position(line0, 0))
    }

    companion object {
        private const val STRATEGY_NAME = "Classpath"

        // URI scheme constants for classpath resolution
        internal const val SCHEME_FILE = "file"
        internal const val SCHEME_JRT = "jrt"
        internal const val SCHEME_JAR = "jar"
    }
}
