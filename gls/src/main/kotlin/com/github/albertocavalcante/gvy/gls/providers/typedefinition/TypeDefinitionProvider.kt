// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.typedefinition

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.CompilationContext
import com.github.albertocavalcante.gvy.gls.compilation.NativeQueryContext
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspLocation
import com.github.albertocavalcante.gvy.gls.providers.inlayhints.reassignedLocals
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.nativeapi.inputDocumentUri
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.transform.stc.StaticTypesMarker
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TypeDefinitionParams
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

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

    private fun findTypeDefinition(uri: URI, position: Position): Location? {
        // One read supplies the AST context and the text its columns index into, so they share a generation.
        val (context, sourceText) = contextProvider(uri) ?: return null

        val node = context.astModel.getNodeAt(uri, position.toGroovyPosition(sourceText)) ?: return null
        return semanticResolver.findLocation(node, context.moduleNode, context.astModel, sourceText)
    }
}

internal fun heldTypeDefinitions(query: NativeQueryContext, uri: URI, position: Position): List<Location> {
    val parsed = query.held.parseResult
    val module = parsed.ast?.takeIf { it.context === parsed.sourceUnit && it.context.inputDocumentUri() == uri }
        ?: return emptyList()
    val node = parsed.astModel.getNodeAt(uri, position.toGroovyPosition(query.held.sourceText)) ?: return emptyList()
    val selected = parsed.astModel.selectedType(node)?.elementDeclaration()
    val location = query.types.findLocation(node, module, parsed.astModel, query.held.sourceText) {
        module.classes.filter { it === selected }
    }
    return listOfNotNull(location)
}

private fun SemanticTypeResolver.findLocation(
    node: ASTNode,
    module: ModuleNode,
    model: GroovyAstModel,
    text: String?,
    localDeclarations: () -> List<ClassNode> = {
        val declared = node.declaredType()?.elementDeclaration()
        module.classes.filter { it.name != declared?.name || it === declared }
    },
): Location? {
    semantics.inject(module)
    val type = resolveType(node, module)
    val declarations = localDeclarations()
    return type.firstLocation { fqn -> declarations.firstOrNull { it.name == fqn }?.toLspLocation(model) { text } }
}

/** An inferred type name cannot distinguish a same-named binary from the source declaration. */
private fun GroovyAstModel.selectedType(use: ASTNode): ClassNode? {
    var node = use
    val seen = Collections.newSetFromMap(IdentityHashMap<ASTNode, Boolean>())
    val reassigned by lazy { reassignedLocals() }
    while (seen.add(node)) {
        node.getNodeMetaData<ClassNode>(StaticTypesMarker.INFERRED_TYPE)?.let { return it }
        val initializer = when (node) {
            is DeclarationExpression -> node.rightExpression
            is VariableExpression -> (node.accessedVariable as? VariableExpression)?.takeIf { it.isDynamicTyped }
                ?.let { binding ->
                    val declaration = getParent(binding) as? DeclarationExpression
                    declaration?.takeIf { it.leftExpression === binding && binding !in reassigned }?.rightExpression
                }
            else -> null
        }
        if (initializer == null) return node.selectedDeclarationType()
        node = initializer
    }
    return null
}

private fun ASTNode.selectedDeclarationType(): ClassNode? = when (this) {
    is VariableExpression -> accessedVariable?.takeUnless { it.isDynamicTyped }?.originType
    is MethodCallExpression -> {
        val target = methodTarget ?: getNodeMetaData<MethodNode>(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
        target?.returnType ?: type
    }
    else -> declaredType()
}

private fun ClassNode.elementDeclaration(): ClassNode {
    var element = this
    while (element.isArray) element = element.componentType
    return element.redirect()
}

private fun ASTNode.declaredType(): ClassNode? = when (this) {
    is ClassNode -> this
    is Variable -> type
    is MethodNode -> returnType
    is Expression -> type
    else -> null
}

// TODO(#615): External definitions need source navigation for the selected class.
private fun SemanticType.firstLocation(local: (String) -> Location?): Location? = when (this) {
    is SemanticType.Known -> local(fqn)
    is SemanticType.Array -> componentType.firstLocation(local)
    is SemanticType.Union -> types.firstNotNullOfOrNull { it.firstLocation(local) }
    else -> null
}
