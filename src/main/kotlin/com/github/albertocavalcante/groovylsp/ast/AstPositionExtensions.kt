package com.github.albertocavalcante.groovylsp.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
/**
 * Kotlin-idiomatic extension functions for position-based AST operations.
 * Provides clean APIs for finding AST nodes at specific positions.
 *
 * TODO: This file has 18 functions, exceeding the original detekt threshold of 13.
 * We've increased the file threshold to 20 for extension files which legitimately contain
 * many utility functions. Future considerations:
 * - Move definition resolution functions (resolveToDefinition, resolveVariableDefinition, etc.)
 *   to DefinitionResolver class as originally planned
 * - Keep only position-related utilities here (containsPosition, findNodeAt, etc.)
 * - Consider splitting into multiple extension files by concern (position, definition, hover)
 */

/**
 * Check if this AST node has invalid position information.
 */
private fun ASTNode.hasInvalidPosition(): Boolean = lineNumber <= 0 ||
    columnNumber <= 0 ||
    lastLineNumber <= 0 ||
    lastColumnNumber <= 0

/**
 * Check if this AST node contains the given position.
 * Public API for tests and external usage.
 */
fun ASTNode.containsPosition(line: Int, column: Int): Boolean {
    if (hasInvalidPosition()) {
        return false
    }

    // Convert to 0-based indexing
    val startLine = lineNumber - 1
    val startColumn = columnNumber - 1
    val endLine = lastLineNumber - 1
    val endColumn = lastColumnNumber - 1

    return when {
        line < startLine || line > endLine -> false
        line == startLine && line == endLine -> {
            column in startColumn..endColumn
        }
        line == startLine -> column >= startColumn
        line == endLine -> column <= endColumn
        else -> true // line is between startLine and endLine
    }
}

/**
 * Find the most specific AST node at the given position.
 * Returns null if no node is found at the position.
 */
fun ModuleNode.findNodeAt(line: Int, column: Int): ASTNode? {
    val visitor = PositionAwareVisitor(line, column)
    visitor.visitModule(this)
    return visitor.smallestNode
}

/**
 * Get the definition node for a reference node using the visitor and symbol table.
 * For example, if hovering over a variable reference, return the variable declaration.
 */
fun ASTNode.getDefinition(visitor: AstVisitor, symbolTable: SymbolTable): ASTNode? = when (this) {
    is VariableExpression -> {
        // First try to get the accessed variable directly
        (accessedVariable as? ASTNode) ?: (symbolTable.resolveSymbol(this, visitor) as? ASTNode)
    }
    is Parameter -> symbolTable.resolveSymbol(this, visitor) as? ASTNode
    is MethodCallExpression -> {
        // Try to resolve the method call to its definition
        visitor.getUri(this)?.let { uri ->
            // For now, just return the first method with matching name
            symbolTable.registry.findMethodDeclarations(uri, method.text).firstOrNull()
        }
    }
    is DeclarationExpression -> {
        // For declaration expressions, return the variable being declared
        leftExpression as? VariableExpression
    }
    else -> null
}

/**
 * Get the original definition node for a reference, similar to fork-groovy-language-server's getDefinition
 */
fun ASTNode.resolveToDefinition(visitor: AstVisitor, symbolTable: SymbolTable, strict: Boolean = true): ASTNode? =
    when (this) {
        is VariableExpression -> resolveVariableDefinition(this)
        is MethodCallExpression -> resolveMethodDefinition(this, symbolTable, visitor)
        is ClassNode, is ClassExpression, is ConstructorCallExpression -> resolveTypeDefinition(this)
        is PropertyExpression -> resolvePropertyExpression(this, visitor, symbolTable)
        is DeclarationExpression -> resolveDeclarationDefinition(this)
        is Parameter, is MethodNode, is FieldNode, is PropertyNode, is ImportNode -> this
        is ConstantExpression -> null // FIXME: String literals are never definitions
        else -> if (strict) null else this
    }

/**
 * Resolve a variable expression to its definition.
 *
 * CRITICAL INSIGHT from fork-groovy-language-server:
 * Return the accessedVariable directly as the definition. This naturally unifies
 * all references to the same variable since:
 * - For declarations: accessedVariable points to itself
 * - For references: accessedVariable points to the declaration
 */
private fun resolveVariableDefinition(expr: VariableExpression): ASTNode? {
    // Return the accessedVariable directly if it's an ASTNode
    return expr.accessedVariable as? ASTNode
}

/**
 * Resolve a method call expression to its definition.
 */
private fun resolveMethodDefinition(
    call: MethodCallExpression,
    symbolTable: SymbolTable,
    visitor: AstVisitor,
): ASTNode? = visitor.getUri(call)?.let { uri ->
    symbolTable.registry.findMethodDeclarations(uri, call.method.text).firstOrNull()
}

/**
 * Resolve type-related nodes to their definitions.
 */
private fun resolveTypeDefinition(node: ASTNode): ASTNode? = when (node) {
    is ClassNode -> node
    is ClassExpression -> node.type
    is ConstructorCallExpression -> node.type
    else -> null
}

/**
 * Resolve a declaration expression to its definition.
 */
private fun resolveDeclarationDefinition(expr: DeclarationExpression): ASTNode? =
    if (!expr.isMultipleAssignmentDeclaration) expr.leftExpression else null

/**
 * Resolve a property expression to its field/property definition.
 */
private fun resolvePropertyExpression(
    propertyExpr: org.codehaus.groovy.ast.expr.PropertyExpression,
    visitor: AstVisitor,
    symbolTable: SymbolTable,
): ASTNode? {
    val propertyName = propertyExpr.propertyAsString
    val targetClass = resolveTargetClass(propertyExpr.objectExpression, visitor, symbolTable, propertyExpr)

    return if (propertyName != null && targetClass != null) {
        findPropertyInClass(targetClass, propertyName, symbolTable)
    } else {
        null
    }
}

/**
 * Resolve the target class from an object expression.
 */
private fun resolveTargetClass(
    objectExpr: org.codehaus.groovy.ast.expr.Expression,
    visitor: AstVisitor,
    symbolTable: SymbolTable,
    context: ASTNode,
): org.codehaus.groovy.ast.ClassNode? = when (objectExpr) {
    is org.codehaus.groovy.ast.expr.VariableExpression ->
        resolveVariableType(objectExpr, visitor, symbolTable, context)
    is org.codehaus.groovy.ast.expr.MethodCallExpression ->
        null // Would require type inference
    else -> null
}

/**
 * Resolve the type of a variable expression.
 */
private fun resolveVariableType(
    varExpr: org.codehaus.groovy.ast.expr.VariableExpression,
    visitor: AstVisitor,
    symbolTable: SymbolTable,
    context: ASTNode,
): org.codehaus.groovy.ast.ClassNode? = when (varExpr.name) {
    "this" -> findEnclosingClass(context, visitor)
    "super" -> findEnclosingClass(context, visitor)?.superClass
    else -> getVariableTypeFromSymbol(varExpr, symbolTable, visitor)
}

/**
 * Find the enclosing class of a given node.
 */
private fun findEnclosingClass(node: ASTNode, visitor: AstVisitor): org.codehaus.groovy.ast.ClassNode? {
    var current = visitor.getParent(node)
    var depth = 0
    while (current != null && current !is org.codehaus.groovy.ast.ClassNode) {
        // WORKAROUND: If we hit a MethodNode, check if it has a declaringClass
        // This handles cases where parent-child relationships don't include ClassNode -> MethodNode
        if (current is org.codehaus.groovy.ast.MethodNode) {
            val declaringClass = current.declaringClass
            if (declaringClass != null && !declaringClass.isScript) {
                return declaringClass
            }
        }

        current = visitor.getParent(current)
        depth++
        if (depth > MAX_PARENT_SEARCH_DEPTH) break // Safety check
    }
    return current as? org.codehaus.groovy.ast.ClassNode
}

private const val MAX_PARENT_SEARCH_DEPTH = 10

/**
 * Get the type of a variable from the symbol table.
 */
private fun getVariableTypeFromSymbol(
    varExpr: org.codehaus.groovy.ast.expr.VariableExpression,
    symbolTable: SymbolTable,
    visitor: AstVisitor,
): org.codehaus.groovy.ast.ClassNode? {
    val resolvedVar = symbolTable.resolveSymbol(varExpr, visitor)
    return when (resolvedVar) {
        is org.codehaus.groovy.ast.Variable -> resolvedVar.type
        is org.codehaus.groovy.ast.FieldNode -> resolvedVar.type
        is org.codehaus.groovy.ast.PropertyNode -> resolvedVar.type
        else -> null
    }
}

/**
 * Find a property in a class.
 */
private fun findPropertyInClass(
    classNode: org.codehaus.groovy.ast.ClassNode,
    propertyName: String,
    symbolTable: SymbolTable,
): ASTNode? = symbolTable.registry.findFieldDeclaration(classNode, propertyName)
    ?: classNode.getField(propertyName)
    ?: classNode.getProperty(propertyName)

/**
 * Types that can provide hover information.
 */
private val HOVERABLE_TYPES = setOf(
    MethodNode::class,
    Variable::class,
    ClassNode::class,
    FieldNode::class,
    PropertyNode::class,
    Parameter::class,
    VariableExpression::class,
    ConstantExpression::class,
    MethodCallExpression::class,
    DeclarationExpression::class,
    BinaryExpression::class,
    ClosureExpression::class,
    GStringExpression::class,
    ImportNode::class,
    PackageNode::class,
    AnnotationNode::class,
    AnnotationConstantExpression::class,
)

/**
 * Check if this node represents a symbol that can provide hover information.
 */
fun ASTNode.isHoverable(): Boolean = HOVERABLE_TYPES.contains(this::class)
