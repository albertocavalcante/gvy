package com.github.albertocavalcante.gvy.gls.providers.inlayhints

import com.github.albertocavalcante.groovyparser.ast.isDynamic
import com.github.albertocavalcante.gvy.common.text.formatTypeName
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Strategy for generating type inlay hints for `def` variable declarations.
 *
 * This strategy shows the inferred type after variable names in declarations
 * where the type is dynamic/def. For example:
 * ```groovy
 * def name = "hello"  // Shows: def name: String = "hello"
 * ```
 *
 * Type hints are only shown when:
 * - The variable is declared with `def` (dynamic type)
 * - The type can be successfully inferred
 * - The inferred type is not Object/def/null/unresolved (provides no useful information)
 */
class TypeInlayHintStrategy : InlayHintStrategy {
    override fun canHandle(node: ASTNode, context: HintContext): Boolean =
        context.config.typeHints && node is DeclarationExpression

    @Suppress("ReturnCount") // Multiple validation checks require early returns
    override fun generateHints(node: ASTNode, context: HintContext): List<InlayHint> {
        val decl = node as DeclarationExpression
        val varExpr = decl.leftExpression as? VariableExpression ?: return emptyList()

        // Only show type hints for dynamic/def declarations
        if (!varExpr.type.isDynamic()) {
            return emptyList()
        }

        val rightExpr = decl.rightExpression
        val semanticType = runCatching { context.semanticResolver.resolveType(rightExpr, context.moduleNode) }
            .getOrElse {
                context.logger.debug(it) { "Failed to resolve type for ${varExpr.name}" }
                return emptyList()
            }
        val inferredType = context.semanticResolver.formatSemanticType(semanticType)

        if (InlayHintsTypes.isDynamicType(inferredType)) {
            // Don't show hints for Object/def (no useful information)
            return emptyList()
        }

        // Format type as simple name (e.g., "ArrayList<Integer>" instead of "java.util.ArrayList<Integer>")
        val displayType = inferredType.formatTypeName()

        // Position the hint after the variable name
        val position = Position(
            varExpr.lineNumber - 1, // Convert to 0-indexed
            varExpr.columnNumber + varExpr.name.length - 1,
        )

        return listOf(
            InlayHint(position, Either.forLeft(": $displayType")).apply {
                kind = InlayHintKind.Type
                paddingLeft = true
            },
        )
    }
}
