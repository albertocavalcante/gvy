package com.github.albertocavalcante.groovylsp.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.Position
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin-based AST visitor that tracks parent-child relationships and URI mappings.
 * Inspired by fork-groovy-language-server's ASTNodeVisitor but with Kotlin idioms.
 */
class AstVisitor : ClassCodeVisitorSupport() {

    private var sourceUnit: SourceUnit? = null
    private var currentUri: URI? = null

    // Parent tracking using a stack - following fork-groovy pattern
    private val nodeStack = ArrayDeque<ASTNode>()

    // Node storage per URI for cross-file support - using thread-safe collections
    private val nodesByUri = ConcurrentHashMap<URI, MutableCollection<ASTNode>>()
    private val classNodesByUri = ConcurrentHashMap<URI, MutableCollection<ClassNode>>()

    // Parent-child relationships
    private val parentMap = ConcurrentHashMap<ASTNode, ASTNode>()

    // URI mapping for nodes
    private val nodeUriMap = ConcurrentHashMap<ASTNode, URI>()

    override fun getSourceUnit(): SourceUnit? = sourceUnit

    /**
     * Visit a module and track all its nodes
     */
    fun visitModule(module: ModuleNode, sourceUnit: SourceUnit, uri: URI) {
        this.sourceUnit = sourceUnit
        this.currentUri = uri

        // Initialize thread-safe collections for this URI
        nodesByUri.computeIfAbsent(uri) { java.util.concurrent.ConcurrentLinkedQueue() }
        classNodesByUri.computeIfAbsent(uri) { java.util.concurrent.ConcurrentLinkedQueue() }

        // Visit the module node itself first
        pushNode(module)

        // Visit all imports in the module
        module.imports?.forEach { importNode ->
            pushNode(importNode)
            popNode()
        }

        // Visit star imports
        module.starImports?.forEach { importNode ->
            pushNode(importNode)
            popNode()
        }

        // Visit static imports
        module.staticImports?.values?.forEach { importNode ->
            pushNode(importNode)
            popNode()
        }

        // Visit static star imports
        module.staticStarImports?.values?.forEach { importNode ->
            pushNode(importNode)
            popNode()
        }

        // Visit all classes in the module (includes the script class for script files)
        module.classes.forEach { classNode ->
            visitClass(classNode)
        }

        // Also visit any script body code if this is a script
        if (module.statementBlock != null) {
            visitBlockStatement(module.statementBlock)
        }

        popNode()
    }

    /**
     * Push a node onto the stack and track relationships
     */
    private fun pushNode(node: ASTNode) {
        // Skip synthetic nodes (following fork-groovy pattern)
        val isSynthetic = when (node) {
            is AnnotatedNode -> node.isSynthetic
            else -> false
        }

        if (!isSynthetic) {
            val uri = currentUri ?: return

            // Add to URI-based storage
            nodesByUri[uri]?.add(node)

            // Track class nodes separately
            if (node is ClassNode) {
                classNodesByUri[uri]?.add(node)
            }

            // Track parent relationship
            nodeStack.lastOrNull()?.let { parent ->
                parentMap[node] = parent
            }

            // Track URI for this node
            nodeUriMap[node] = uri
        }

        nodeStack.addLast(node)
    }

    /**
     * Pop the current node from the stack
     */
    private fun popNode() {
        nodeStack.removeLastOrNull()
    }

    /**
     * Get the parent of a given node
     */
    fun getParent(node: ASTNode): ASTNode? = parentMap[node]

    /**
     * Get the URI for a given node
     */
    fun getUri(node: ASTNode): URI? = nodeUriMap[node]

    /**
     * Get all nodes for a specific URI
     */
    fun getNodes(uri: URI): List<ASTNode> = nodesByUri[uri]?.toList() ?: emptyList()

    /**
     * Get all nodes across all URIs
     */
    fun getAllNodes(): List<ASTNode> = nodesByUri.values.flatten()

    /**
     * Get all class nodes
     */
    fun getAllClassNodes(): List<ClassNode> = classNodesByUri.values.flatten()

    /**
     * Find the node at a specific LSP position (0-based coordinates)
     * Uses position utility for consistent coordinate conversion
     */
    fun getNodeAt(uri: URI, lspPosition: Position): ASTNode? = getNodeAt(uri, lspPosition.line, lspPosition.character)

    /**
     * Find the node at a specific line and column (LSP 0-based coordinates)
     * Uses the same logic as fork-groovy-language-server
     */
    fun getNodeAt(uri: URI, lspLine: Int, lspCharacter: Int): ASTNode? {
        val nodes = getNodes(uri)
        val lspPosition = Position(lspLine, lspCharacter)

        val candidateNodes = nodes.filter { node ->
            PositionUtils.isPositionInNode(lspPosition, node)
        }

        // Sort by most specific (smallest range first), then by priority
        return candidateNodes
            .sortedWith { n1, n2 ->
                val range1 = PositionUtils.astNodeToRange(n1)
                val range2 = PositionUtils.astNodeToRange(n2)

                when {
                    range1 == null && range2 == null -> 0
                    range1 == null -> 1
                    range2 == null -> -1
                    else -> {
                        val size1 = PositionUtils.calculateRangeSize(range1)
                        val size2 = PositionUtils.calculateRangeSize(range2)
                        when {
                            size1 != size2 -> size1.compareTo(size2)
                            else -> n2.priority().weight.compareTo(n1.priority().weight)
                        }
                    }
                }
            }
            .firstOrNull()
    }

    /**
     * Check if ancestor contains descendant in the AST hierarchy
     */
    fun contains(ancestor: ASTNode, descendant: ASTNode): Boolean {
        var current = getParent(descendant)
        while (current != null) {
            if (current == ancestor) return true
            current = getParent(current)
        }
        return false
    }

    /**
     * Clear all cached data
     */
    fun clear() {
        nodeStack.clear()
        nodesByUri.clear()
        classNodesByUri.clear()
        parentMap.clear()
        nodeUriMap.clear()
    }

    /**
     * Visit annotations on an annotated node
     */
    override fun visitAnnotations(node: AnnotatedNode) {
        node.annotations?.forEach { annotation ->
            pushNode(annotation)
            try {
                // Visit annotation members if present
                annotation.members?.forEach { (_, value) ->
                    if (value is org.codehaus.groovy.ast.expr.Expression) {
                        value.visit(this)
                    }
                }
            } finally {
                popNode()
            }
        }
    }

    // Override visitor methods to track nodes

    override fun visitClass(node: ClassNode) {
        pushNode(node)
        visitAnnotations(node)
        try {
            super.visitClass(node)
        } finally {
            popNode()
        }
    }

    override fun visitMethod(node: MethodNode) {
        pushNode(node)
        visitAnnotations(node)
        try {
            // Visit parameters
            node.parameters?.forEach { param ->
                pushNode(param)
                popNode()
            }
            super.visitMethod(node)
        } finally {
            popNode()
        }
    }

    override fun visitField(node: FieldNode) {
        pushNode(node)
        visitAnnotations(node)
        try {
            super.visitField(node)
        } finally {
            popNode()
        }
    }

    override fun visitProperty(node: PropertyNode) {
        pushNode(node)
        visitAnnotations(node)
        try {
            super.visitProperty(node)
        } finally {
            popNode()
        }
    }

    // Expression visitors

    override fun visitMethodCallExpression(call: MethodCallExpression) {
        pushNode(call)
        try {
            super.visitMethodCallExpression(call)
        } finally {
            popNode()
        }
    }

    override fun visitVariableExpression(expression: VariableExpression) {
        pushNode(expression)
        try {
            super.visitVariableExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitDeclarationExpression(expression: DeclarationExpression) {
        pushNode(expression)
        try {
            super.visitDeclarationExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitBinaryExpression(expression: BinaryExpression) {
        pushNode(expression)
        try {
            super.visitBinaryExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitPropertyExpression(expression: PropertyExpression) {
        pushNode(expression)
        try {
            super.visitPropertyExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
        pushNode(call)
        try {
            super.visitConstructorCallExpression(call)
        } finally {
            popNode()
        }
    }

    override fun visitClassExpression(expression: ClassExpression) {
        pushNode(expression)
        try {
            super.visitClassExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitClosureExpression(expression: ClosureExpression) {
        pushNode(expression)
        try {
            // Visit closure parameters
            expression.parameters?.forEach { param ->
                pushNode(param)
                popNode()
            }
            super.visitClosureExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitGStringExpression(expression: org.codehaus.groovy.ast.expr.GStringExpression) {
        pushNode(expression)
        try {
            super.visitGStringExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitConstantExpression(expression: org.codehaus.groovy.ast.expr.ConstantExpression) {
        pushNode(expression)
        try {
            super.visitConstantExpression(expression)
        } finally {
            popNode()
        }
    }

    // Statement visitors

    override fun visitBlockStatement(block: BlockStatement) {
        pushNode(block)
        try {
            super.visitBlockStatement(block)
        } finally {
            popNode()
        }
    }

    override fun visitExpressionStatement(statement: ExpressionStatement) {
        pushNode(statement)
        try {
            super.visitExpressionStatement(statement)
        } finally {
            popNode()
        }
    }

    // Additional statement visitors for complete coverage

    override fun visitForLoop(loop: org.codehaus.groovy.ast.stmt.ForStatement) {
        pushNode(loop)
        try {
            super.visitForLoop(loop)
        } finally {
            popNode()
        }
    }

    override fun visitWhileLoop(loop: org.codehaus.groovy.ast.stmt.WhileStatement) {
        pushNode(loop)
        try {
            super.visitWhileLoop(loop)
        } finally {
            popNode()
        }
    }

    override fun visitIfElse(ifElse: org.codehaus.groovy.ast.stmt.IfStatement) {
        pushNode(ifElse)
        try {
            super.visitIfElse(ifElse)
        } finally {
            popNode()
        }
    }

    override fun visitTryCatchFinally(statement: org.codehaus.groovy.ast.stmt.TryCatchStatement) {
        pushNode(statement)
        try {
            super.visitTryCatchFinally(statement)
        } finally {
            popNode()
        }
    }

    override fun visitReturnStatement(statement: org.codehaus.groovy.ast.stmt.ReturnStatement) {
        pushNode(statement)
        try {
            super.visitReturnStatement(statement)
        } finally {
            popNode()
        }
    }

    override fun visitThrowStatement(statement: org.codehaus.groovy.ast.stmt.ThrowStatement) {
        pushNode(statement)
        try {
            super.visitThrowStatement(statement)
        } finally {
            popNode()
        }
    }
}

/**
 * Helper extension to calculate range size for node prioritization
 */
private fun ASTNode.calculateRangeSize(): Int {
    if (lineNumber < 0 || columnNumber < 0 || lastLineNumber < 0 || lastColumnNumber < 0) {
        return Int.MAX_VALUE
    }

    val lineSpan = lastLineNumber - lineNumber
    val columnSpan = if (lineSpan == 0) {
        lastColumnNumber - columnNumber
    } else {
        lineSpan * COLUMN_WEIGHT_MULTIPLIER + lastColumnNumber
    }

    return lineSpan * LINE_WEIGHT_MULTIPLIER + columnSpan
}

private const val LINE_WEIGHT_MULTIPLIER = 1000
private const val COLUMN_WEIGHT_MULTIPLIER = 100
