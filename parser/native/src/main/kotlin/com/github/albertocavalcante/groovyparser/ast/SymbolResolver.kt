package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Resolves symbols to their definitions using registry data.
 * Extracted from SymbolTable to provide focused resolution logic.
 */
class SymbolResolver(private val registry: SymbolRegistry) {

    /**
     * Resolve a symbol to its definition.
     */
    fun resolveSymbol(node: ASTNode, visitor: GroovyAstModel): Variable? {
        val uri = visitor.getUri(node) ?: return null

        return when (node) {
            is VariableExpression -> {
                // First try to find as local variable
                registry.findVariableDeclaration(uri, node.name)
                    // Fall back to field search
                    ?: findFieldInScope(node, visitor)
            }
            else -> null
        }
    }

    /**
     * Find a field in the current scope.
     */
    private fun findFieldInScope(variableExpr: VariableExpression, visitor: GroovyAstModel): Variable? {
        val searchContext = getFieldSearchContext(variableExpr, visitor) ?: return null

        return searchContext.entries
            .firstOrNull { (fieldName, _) -> fieldName == variableExpr.name }
            ?.let { findFieldInEnclosingClass(variableExpr, visitor) }
    }

    /**
     * Find a field in the enclosing class.
     */
    private fun findFieldInEnclosingClass(variableExpr: VariableExpression, visitor: GroovyAstModel): Variable? {
        // Walk up the AST to find the enclosing class
        var current = visitor.getParent(variableExpr)
        while (current != null && current !is ClassNode) {
            current = visitor.getParent(current)
        }

        val enclosingClass = current
        if (enclosingClass !is ClassNode) {
            return null
        }

        // Look for the field in the class
        return enclosingClass.getField(variableExpr.name)
    }

    /**
     * Get the field search context for a variable expression.
     */
    private fun getFieldSearchContext(
        variableExpr: VariableExpression,
        visitor: GroovyAstModel,
    ): Map<String, ClassNode>? {
        val uri = visitor.getUri(variableExpr) ?: return null
        val classDeclarations = registry.getClassDeclarations(uri)
        return if (classDeclarations.isNotEmpty()) classDeclarations else null
    }
}
