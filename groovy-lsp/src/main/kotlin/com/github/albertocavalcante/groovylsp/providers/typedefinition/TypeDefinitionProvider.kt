package com.github.albertocavalcante.groovylsp.providers.typedefinition

import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.converters.toLspLocation
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TypeDefinitionParams
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Provides type definition functionality for Groovy language server.
 * When a user requests "Go to Type Definition", this provider resolves the type
 * of the symbol at the cursor and returns the location where that type is defined.
 */
class TypeDefinitionProvider(
    private val coroutineScope: CoroutineScope,
    private val semanticResolver: SemanticTypeResolver,
    private val sourceNavigator: SourceNavigator, // TODO: Will be used for external class resolution (see issue #615).
    private val contextProvider: (URI) -> CompilationContext?,
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Provides type definition for the symbol at the given position.
     */
    @Suppress("TooGenericExceptionCaught")
    fun provideTypeDefinition(params: TypeDefinitionParams): CompletableFuture<List<Location>> = coroutineScope.future {
        try {
            val uri = URI.create(params.textDocument.uri)
            val position = params.position

            logger.debug { "Type definition requested for ${params.textDocument.uri} at $position" }

            findTypeDefinition(uri, position)?.let { location ->
                logger.debug { "Found type definition at: $location" }
                listOf(location)
            } ?: run {
                logger.debug { "No type definition found" }
                emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Error providing type definition" }
            emptyList()
        }
    }

    private suspend fun findTypeDefinition(uri: URI, position: Position): Location? {
        val context = contextProvider(uri) ?: return null

        // Inject module node for semantic analysis
        context.moduleNode?.let { semanticResolver.semantics.inject(it) }

        // Find the AST node at the given position
        val node = context.astModel.getNodeAt(uri, position.toGroovyPosition()) ?: run {
            logger.debug { "No AST node found at position $position" }
            return null
        }
        logger.debug { "Found AST node: ${node.javaClass.simpleName} at position $position" }

        // Resolve semantic type
        val semanticType = semanticResolver.resolveType(node, context.moduleNode)
        logger.debug { "Resolved semantic type: $semanticType" }

        // Convert to location
        val location = resolveTypeLocation(semanticType, context)
        logger.debug { "Resolved location: $location" }
        return location
    }

    private suspend fun resolveTypeLocation(type: SemanticType, context: CompilationContext): Location? = when (type) {
        is SemanticType.Known -> findClassLocation(type.fqn, context)
        is SemanticType.Array -> resolveTypeLocation(type.componentType, context)
        is SemanticType.Union -> {
            // Return first resolvable type location
            type.types.firstNotNullOfOrNull { resolveTypeLocation(it, context) }
        }
        else -> null
    }

    private suspend fun findClassLocation(fqn: String, context: CompilationContext): Location? {
        logger.debug { "Looking for class location for FQN: '$fqn'" }
        logger.debug { "Available classes in module: ${context.moduleNode.classes.map { it.name }}" }

        // 1. Check if defined in current file/module
        // This handles classes defined in the same file we are currently editing.
        context.moduleNode.classes.find { it.name == fqn }?.let { classNode ->
            logger.debug { "Found class in module: ${classNode.name}" }
            val location = classNode.toLspLocation(context.astModel)
            logger.debug { "Converted to LSP location: $location" }
            return location
        }

        logger.debug { "Class not found in module, would need external resolution" }

        // 2. External Class Resolution
        // TODO(#615): Implement Go-To-Type-Definition for external classes (JARs, JDK).
        //
        // Current Limitation:
        // We cannot currently resolve the location of classes defined outside the current file.
        // The `context` object does not provide a `findClass(fqn)` method to look up
        // external ClassNodes or their source URIs.
        //
        // Required Implementation Steps:
        // 1. Expose `ClasspathService` or a similar lookup mechanism to `TypeDefinitionProvider`.
        // 2. Use `classpathService.findClasspathClass(fqn)` to get the binary URI (jar:file:...).
        // 3. Pass this URI to `sourceNavigator.navigateToSource(uri, fqn)`.
        // 4. If source is found, return the `Location` with the correct URI and range.
        //
        // For now, we return null, which means the client will stay at the current cursor position
        // or show a "Definition not found" message for external types.

        return null
    }
}

/**
 * Factory for creating TypeDefinitionProvider instances.
 * Provides a clean way to construct providers with proper dependencies.
 */
object TypeDefinitionProviderFactory {
    fun create(
        coroutineScope: CoroutineScope,
        semanticResolver: SemanticTypeResolver,
        sourceNavigator: SourceNavigator,
        contextProvider: (URI) -> CompilationContext?,
    ): TypeDefinitionProvider = TypeDefinitionProvider(
        coroutineScope = coroutineScope,
        semanticResolver = semanticResolver,
        sourceNavigator = sourceNavigator,
        contextProvider = contextProvider,
    )
}
