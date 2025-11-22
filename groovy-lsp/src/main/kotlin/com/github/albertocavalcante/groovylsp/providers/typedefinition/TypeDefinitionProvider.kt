package com.github.albertocavalcante.groovylsp.providers.typedefinition

import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.types.TypeResolver
import kotlinx.coroutines.CoroutineScope
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TypeDefinitionParams
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Provides type definition functionality for Groovy language server.
 * When a user requests "Go to Type Definition", this provider resolves the type
 * of the symbol at the cursor and returns the location where that type is defined.
 *
 * Based on patterns from fork-groovy-language-server TypeDefinitionProvider,
 * but enhanced with our improved type resolution system.
 */
class TypeDefinitionProvider(
    private val coroutineScope: CoroutineScope,
    private val typeResolver: TypeResolver,
    private val contextProvider: (URI) -> CompilationContext?,
) {

    private val logger = LoggerFactory.getLogger(TypeDefinitionProvider::class.java)

    /**
     * Provides type definition for the symbol at the given position.
     *
     * @param params LSP TypeDefinitionParams containing document URI and position
     * @return CompletableFuture with Location of the type definition, or empty list if not found
     */
    @Suppress("TooGenericExceptionCaught") // Final fallback for any unexpected runtime errors
    fun provideTypeDefinition(params: TypeDefinitionParams): CompletableFuture<List<Location>> = coroutineScope.future {
        try {
            val uri = URI.create(params.textDocument.uri)
            val position = params.position

            logger.debug("Type definition requested for ${params.textDocument.uri} at $position")

            findTypeDefinition(uri, position)?.let { location ->
                logger.debug("Found type definition at: $location")
                listOf(location)
            } ?: run {
                logger.debug("No type definition found")
                emptyList()
            }
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments for type definition", e)
            emptyList()
        } catch (e: NullPointerException) {
            logger.error("Null reference in type definition processing", e)
            emptyList()
        } catch (e: RuntimeException) {
            logger.error("Runtime error providing type definition", e)
            emptyList()
        }
    }

    /**
     * Finds the type definition for the symbol at the given position.
     *
     * @param uri The document URI
     * @param position The cursor position
     * @return Location of the type definition, or null if not found
     */
    private suspend fun findTypeDefinition(uri: URI, position: Position): Location? {
        val context = contextProvider(uri) ?: run {
            logger.debug("No compilation context available for $uri")
            return null
        }

        // Find the AST node at the given position
        val node = context.astVisitor.getNodeAt(uri, position.toGroovyPosition()) ?: run {
            logger.debug("No AST node found at position $position")
            return null
        }

        logger.debug("Found AST node: ${node.javaClass.simpleName}")

        // Resolve the type definition using our TypeResolver
        return typeResolver.resolveTypeDefinition(node, context)
    }
}

/**
 * Factory for creating TypeDefinitionProvider instances.
 * Provides a clean way to construct providers with proper dependencies.
 */
object TypeDefinitionProviderFactory {
    fun create(
        coroutineScope: CoroutineScope,
        typeResolver: TypeResolver,
        contextProvider: (URI) -> CompilationContext?,
    ): TypeDefinitionProvider = TypeDefinitionProvider(
        coroutineScope = coroutineScope,
        typeResolver = typeResolver,
        contextProvider = contextProvider,
    )
}
