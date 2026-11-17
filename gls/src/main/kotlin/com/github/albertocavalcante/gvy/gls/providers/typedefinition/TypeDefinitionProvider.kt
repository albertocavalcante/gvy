// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.typedefinition

import com.github.albertocavalcante.gvy.gls.compilation.CompilationContext
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspLocation
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import com.github.albertocavalcante.gvy.semantics.SemanticType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TypeDefinitionParams
import java.net.URI

/**
 * Provides type definition functionality for Groovy language server.
 * When a user requests "Go to Type Definition", this provider resolves the type
 * of the symbol at the cursor and returns the location where that type is defined.
 */
class TypeDefinitionProvider(
    private val semanticResolver: SemanticTypeResolver,
    private val contextProvider: (URI) -> Pair<CompilationContext, String?>?,
) {

    private val logger = KotlinLogging.logger {}

    /** The type definitions at [params]; a cancelled request ends cancelled, not with an empty answer. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun typeDefinitions(params: TypeDefinitionParams): List<Location> = try {
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
        rethrowIfCancellationOrError(e)
        logger.error(e) { "Error providing type definition" }
        emptyList()
    }

    private suspend fun findTypeDefinition(uri: URI, position: Position): Location? {
        // One read supplies the AST context and the text its columns index into, so they share a generation.
        val (context, sourceText) = contextProvider(uri) ?: return null

        // Inject module node for semantic analysis
        semanticResolver.semantics.inject(context.moduleNode)

        // Find the AST node at the given position
        val node = context.astModel.getNodeAt(uri, position.toGroovyPosition(sourceText)) ?: run {
            logger.debug { "No AST node found at position $position" }
            return null
        }
        logger.debug { "Found AST node: ${node.javaClass.simpleName} at position $position" }

        // Resolve semantic type
        val semanticType = semanticResolver.resolveType(node, context.moduleNode)
        logger.debug { "Resolved semantic type: $semanticType" }

        // Convert to location
        val location = resolveTypeLocation(semanticType, context, sourceText)
        logger.debug { "Resolved location: $location" }
        return location
    }

    private suspend fun resolveTypeLocation(
        type: SemanticType,
        context: CompilationContext,
        sourceText: String?,
    ): Location? = when (type) {
        is SemanticType.Known -> findClassLocation(type.fqn, context, sourceText)
        is SemanticType.Array -> resolveTypeLocation(type.componentType, context, sourceText)
        is SemanticType.Union -> {
            // Return first resolvable type location
            type.types.firstNotNullOfOrNull { resolveTypeLocation(it, context, sourceText) }
        }
        else -> null
    }

    private suspend fun findClassLocation(fqn: String, context: CompilationContext, sourceText: String?): Location? {
        logger.debug { "Looking for class location for FQN: '$fqn'" }
        logger.debug { "Available classes in module: ${context.moduleNode.classes.map { it.name }}" }

        // 1. Check if defined in current file/module
        // This handles classes defined in the same file we are currently editing.
        context.moduleNode.classes.find { it.name == fqn }?.let { classNode ->
            logger.debug { "Found class in module: ${classNode.name}" }
            // A class of this module is declared in the document whose text [sourceText] is.
            val location = classNode.toLspLocation(context.astModel) { sourceText }
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
