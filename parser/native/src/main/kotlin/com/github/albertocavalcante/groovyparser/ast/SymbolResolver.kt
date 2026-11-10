package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import java.util.Collections
import java.util.IdentityHashMap

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
                resolveVariableInScope(uri, node, visitor)
                    ?: findFieldInScope(node, visitor)
            }
            else -> null
        }
    }

    private fun resolveVariableInScope(
        uri: java.net.URI,
        node: VariableExpression,
        visitor: GroovyAstModel,
    ): Variable? {
        val candidates = registry.findVariableDeclarations(uri, node.name)
        if (candidates.isEmpty()) {
            return null
        }
        if (candidates.size == 1) {
            return candidates.first()
        }

        val directMatch = candidates.firstOrNull { it === node }
        if (directMatch != null) {
            return directMatch
        }

        val scopeChain = buildScopeChain(node, visitor)
        val allCandidates = candidates.mapNotNull { candidate ->
            val candidateNode = candidate as? ASTNode ?: return@mapNotNull null
            val scope = findEnclosingScope(candidateNode, visitor)
            val scopeIndex = scope?.let { scopeChain.indexOfFirst { chainScope -> chainScope === it } } ?: -1
            Candidate(
                variable = candidate,
                scopeIndex = if (scopeIndex == -1) Int.MAX_VALUE else scopeIndex,
                line = candidateNode.lineNumber,
                column = candidateNode.columnNumber,
                isBeforeReference = isBeforeReference(candidateNode, node),
            )
        }

        return selectBestCandidate(allCandidates)
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
        val enclosingClass = ancestors(variableExpr, visitor).firstOrNull { it is ClassNode } as? ClassNode
            ?: return null

        // Look for the field in the class
        return enclosingClass.findFieldSafe(variableExpr.name)
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

    private fun isBeforeReference(candidate: ASTNode, reference: ASTNode): Boolean =
        candidate.lineNumber < reference.lineNumber ||
            (candidate.lineNumber == reference.lineNumber && candidate.columnNumber < reference.columnNumber)

    private fun buildScopeChain(node: ASTNode, visitor: GroovyAstModel): List<ASTNode> =
        ancestors(node, visitor).filter(::isScopeBoundary).toList()

    private fun findEnclosingScope(node: ASTNode, visitor: GroovyAstModel): ASTNode? =
        ancestors(node, visitor).firstOrNull(::isScopeBoundary)

    /**
     * [node]'s ancestors, nearest first, ending at the root or at the first node seen twice: a cycle in the model's
     * parent links must not hang resolution (#1411). There is deliberately no depth cap: the parent map is finite, so
     * the visited set alone ends the walk, and a cap could only cut a real deep chain (a long concatenation, say)
     * short and turn it into a silent "not found".
     */
    private fun ancestors(node: ASTNode, visitor: GroovyAstModel): Sequence<ASTNode> = sequence {
        val seen = Collections.newSetFromMap(IdentityHashMap<ASTNode, Boolean>())
        var current = visitor.getParent(node)
        while (current != null && seen.add(current)) {
            yield(current)
            current = visitor.getParent(current)
        }
    }

    private fun isScopeBoundary(node: ASTNode): Boolean = node is MethodNode ||
        node is ConstructorNode ||
        node is ClosureExpression ||
        node is BlockStatement ||
        node is ClassNode

    private fun selectBestCandidate(candidates: List<Candidate>): Variable? {
        if (candidates.isEmpty()) {
            return null
        }

        val bestScopeIndex = candidates.minOf { it.scopeIndex }
        val scopedCandidates = candidates.filter { it.scopeIndex == bestScopeIndex }

        val beforeCandidates = scopedCandidates.filter { it.isBeforeReference }
        val comparator = compareBy<Candidate> { it.line }.thenBy { it.column }

        val best = if (beforeCandidates.isNotEmpty()) {
            beforeCandidates.maxWithOrNull(comparator)
        } else {
            scopedCandidates.minWithOrNull(comparator)
        }
        return best?.variable
    }

    private data class Candidate(
        val variable: Variable,
        val scopeIndex: Int,
        val line: Int,
        val column: Int,
        val isBeforeReference: Boolean,
    )
}
