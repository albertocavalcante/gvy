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
import org.codehaus.groovy.ast.expr.ArgumentListExpression
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
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
/**
 * Kotlin-idiomatic extension functions for position-based AST operations.
 * Provides clean APIs for finding AST nodes at specific positions.
 */

object AstConstants {
    const val LINE_MULTIPLIER = 1000
    const val MULTILINE_MULTIPLIER = 100
}

/**
 * Node priority for resolving conflicts when multiple nodes cover the same position.
 * Higher weights indicate higher priority.
 */
sealed class NodePriority(val weight: Int) {
    companion object {
        private const val LITERAL_PRIORITY = 0
        private const val REFERENCE_PRIORITY = 1
        private const val CALL_PRIORITY = 2
        private const val DECLARATION_PRIORITY = 3
        private const val DEFINITION_PRIORITY = 4
    }

    object Literal : NodePriority(LITERAL_PRIORITY) // ConstantExpression
    object Reference : NodePriority(REFERENCE_PRIORITY) // VariableExpression
    object Call : NodePriority(CALL_PRIORITY) // MethodCallExpression
    object Declaration : NodePriority(DECLARATION_PRIORITY) // DeclarationExpression, BinaryExpression
    object Definition : NodePriority(DEFINITION_PRIORITY) // MethodNode, ClassNode, Parameter
}

/**
 * Get the priority of this AST node for selection purposes.
 */
fun ASTNode.priority(): NodePriority = when (this) {
    is MethodNode, is ClassNode, is FieldNode, is PropertyNode, is Parameter -> NodePriority.Definition
    // For hover purposes, we want the most specific content the user is hovering over
    is GStringExpression -> NodePriority.Definition // High priority for GString expressions
    is ConstantExpression -> NodePriority.Definition // High priority for constants
    is DeclarationExpression -> NodePriority.Declaration
    is BinaryExpression -> NodePriority.Declaration
    is MethodCallExpression -> NodePriority.Call
    is VariableExpression -> NodePriority.Reference
    else -> NodePriority.Literal
}

/**
 * Check if this AST node contains the given position (0-based line and column).
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

    // Visit all nodes in the module
    visitor.visitModule(this)

    return visitor.smallestNode
}

/**
 * Calculate the size of the range covered by this AST node.
 * Used for finding the smallest (most specific) node at a position.
 */
private fun ASTNode.calculateRangeSize(): Int {
    if (hasInvalidPosition()) {
        return Int.MAX_VALUE // Invalid position should have lowest priority
    }

    val lineSpan = (lastLineNumber - lineNumber)
    val columnSpan = if (lineSpan == 0) {
        lastColumnNumber - columnNumber
    } else {
        // Multi-line nodes: approximate size
        lineSpan * AstConstants.MULTILINE_MULTIPLIER + lastColumnNumber
    }

    return lineSpan * AstConstants.LINE_MULTIPLIER + columnSpan
}

/**
 * Check if this AST node has invalid position information.
 */
private fun ASTNode.hasInvalidPosition(): Boolean =
    lineNumber < 0 || columnNumber < 0 || lastLineNumber < 0 || lastColumnNumber < 0

/**
 * Visitor that finds the smallest node containing a specific position.
 * Uses a custom visitor pattern optimized for position lookups.
 */
private class PositionAwareVisitor(private val targetLine: Int, private val targetColumn: Int) {
    var smallestNode: ASTNode? = null
    private var smallestRangeSize = Int.MAX_VALUE

    fun visitModule(module: ModuleNode) {
        // Visit package node if present
        module.getPackage()?.let {
            checkAndUpdateSmallest(it)
        }

        // Visit all imports
        module.imports?.forEach { importNode ->
            checkAndUpdateSmallest(importNode)
        }

        // Visit star imports
        module.starImports?.forEach { importNode ->
            checkAndUpdateSmallest(importNode)
        }

        // Visit static imports (returns Map<String, ImportNode>)
        module.staticImports?.values?.forEach { importNode ->
            checkAndUpdateSmallest(importNode)
        }

        // Visit static star imports (returns Map<String, ImportNode>)
        module.staticStarImports?.values?.forEach { importNode ->
            checkAndUpdateSmallest(importNode)
        }

        // Visit all classes in the module
        module.classes.forEach { visitClass(it) }
    }

    private fun visitClass(classNode: ClassNode) {
        checkAndUpdateSmallest(classNode)

        // Visit methods
        classNode.methods.forEach { visitMethod(it) }

        // Visit fields
        classNode.fields.forEach { visitField(it) }

        // Visit properties
        classNode.properties.forEach { visitProperty(it) }
    }

    private fun visitMethod(method: MethodNode) {
        checkAndUpdateSmallest(method)

        // Visit parameters so hovering over them works
        method.parameters.forEach { param ->
            checkAndUpdateSmallest(param)
        }

        // Visit method body if available
        method.code?.let { visitNode(it) }
    }

    private fun visitField(field: FieldNode) {
        checkAndUpdateSmallest(field)

        // Visit field initializer if available
        field.initialExpression?.let { visitExpression(it) }
    }

    private fun visitProperty(property: PropertyNode) {
        checkAndUpdateSmallest(property)

        // Visit property initializer if available
        property.initialExpression?.let { visitExpression(it) }
    }

    private fun visitExpression(expression: Expression) {
        checkAndUpdateSmallest(expression)

        when (expression) {
            is MethodCallExpression -> {
                visitExpression(expression.objectExpression)
                // DON'T visit method name separately - it conflicts with the call itself
                // visitExpression(expression.method)
                (expression.arguments as? ArgumentListExpression)?.expressions?.forEach {
                    visitExpression(it)
                }
            }
            is VariableExpression -> {
                // Variable expressions are leaf nodes, already handled by checkAndUpdateSmallest
            }
            is ConstantExpression -> {
                // Constant expressions (strings, numbers, etc.) are leaf nodes
                // Already handled by checkAndUpdateSmallest
            }
            is ArgumentListExpression -> {
                // Visit all argument expressions
                expression.expressions.forEach { visitExpression(it) }
            }
            is DeclarationExpression -> {
                // Visit the variable being declared and the right-hand side
                visitExpression(expression.leftExpression)
                visitExpression(expression.rightExpression)
            }
            is BinaryExpression -> {
                // Visit both sides of the binary expression
                visitExpression(expression.leftExpression)
                visitExpression(expression.rightExpression)
            }
            is ClosureExpression -> {
                // Visit closure parameters
                expression.parameters?.forEach { param ->
                    checkAndUpdateSmallest(param)
                }

                // Visit closure body
                expression.code?.let { visitNode(it) }
            }
            is GStringExpression -> {
                // Visit all string constant parts
                expression.strings?.forEach { stringExpr ->
                    checkAndUpdateSmallest(stringExpr)
                }

                // Visit all interpolated value expressions
                expression.values?.forEach { valueExpr ->
                    visitExpression(valueExpr)
                }
            }
            // Add more expression types as needed
        }
    }

    private fun visitNode(node: ASTNode) {
        checkAndUpdateSmallest(node)

        // Handle different node types
        when (node) {
            is Statement -> visitStatement(node)
            is Expression -> visitExpression(node)
            // Add more node type handling as needed
        }
    }

    private fun visitStatement(statement: Statement) {
        checkAndUpdateSmallest(statement)

        when (statement) {
            is BlockStatement -> {
                // Visit all statements in the block
                statement.statements.forEach { visitStatement(it) }
            }
            is ExpressionStatement -> {
                // Visit the expression within the statement
                visitExpression(statement.expression)
            }
            // Add more statement types as needed
        }
    }

    private fun checkAndUpdateSmallest(node: ASTNode) {
        if (node.containsPosition(targetLine, targetColumn)) {
            val rangeSize = node.calculateRangeSize()
            val currentPriority = node.priority().weight
            val existingPriority = smallestNode?.priority()?.weight ?: -1

            // Primarily prefer smallest nodes, with priority as tiebreaker
            val shouldReplace = when {
                smallestNode == null -> true
                // Prefer smaller nodes
                rangeSize < smallestRangeSize -> true
                // For equal size, prefer higher priority
                rangeSize == smallestRangeSize -> currentPriority > existingPriority
                // Don't replace with larger nodes
                else -> false
            }

            if (shouldReplace) {
                smallestNode = node
                smallestRangeSize = rangeSize
            }
        }
    }
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
            symbolTable.findMethodDeclarations(uri, method.text).firstOrNull()
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
fun ASTNode.resolveToDefinition(visitor: AstVisitor, symbolTable: SymbolTable, strict: Boolean = true): ASTNode? {
    val parent = visitor.getParent(this)

    return when (this) {
        is VariableExpression -> {
            // Look up variable declaration in symbol table
            symbolTable.resolveSymbol(this, visitor) as? ASTNode
        }
        is MethodCallExpression -> {
            // Try to resolve method call
            visitor.getUri(this)?.let { uri ->
                val methods = symbolTable.findMethodDeclarations(uri, method.text)
                if (methods.isNotEmpty()) methods.first() else null
            }
        }
        is ClassNode -> {
            // For class nodes, they are their own definition unless looking for imported classes
            this
        }
        is ClassExpression -> {
            // ClassExpression references the type directly
            type
        }
        is ConstructorCallExpression -> {
            // Constructor call references the class being instantiated
            type
        }
        is PropertyExpression -> {
            // Try to resolve property access to field/property definition
            resolvePropertyExpression(this, visitor, symbolTable)
        }
        is DeclarationExpression -> {
            // Return the variable being declared
            if (!isMultipleAssignmentDeclaration) {
                leftExpression
            } else {
                null
            }
        }
        is Parameter -> this // Parameters are their own definition
        is MethodNode -> this // Methods are their own definition
        is FieldNode -> this // Fields are their own definition
        is PropertyNode -> this // Properties are their own definition
        is ImportNode -> this // Imports are their own definition
        is ConstantExpression -> {
            // Check if this constant is part of a method call or property access
            parent?.let { parentNode ->
                when (parentNode) {
                    is MethodCallExpression -> parentNode.resolveToDefinition(visitor, symbolTable, strict)
                    else -> if (strict) null else this
                }
            }
        }
        else -> if (strict) null else this
    }
}

/**
 * Resolve a property expression to its field/property definition.
 */
private fun resolvePropertyExpression(
    propertyExpr: org.codehaus.groovy.ast.expr.PropertyExpression,
    visitor: AstVisitor,
    symbolTable: SymbolTable,
): ASTNode? {
    val propertyName = propertyExpr.propertyAsString ?: return null

    // Try to resolve the object expression first to get the class
    val objectExpr = propertyExpr.objectExpression
    val targetClass = when (objectExpr) {
        is org.codehaus.groovy.ast.expr.VariableExpression -> {
            when (objectExpr.name) {
                "this" -> {
                    // Find the enclosing class for "this" reference
                    var current = visitor.getParent(propertyExpr)
                    while (current != null && current !is org.codehaus.groovy.ast.ClassNode) {
                        current = visitor.getParent(current)
                    }
                    current as? org.codehaus.groovy.ast.ClassNode
                }
                "super" -> {
                    // Handle super.field access
                    var current = visitor.getParent(propertyExpr)
                    while (current != null && current !is org.codehaus.groovy.ast.ClassNode) {
                        current = visitor.getParent(current)
                    }
                    (current as? org.codehaus.groovy.ast.ClassNode)?.superClass
                }
                else -> {
                    // Regular variable - resolve to its type
                    val resolvedVar = symbolTable.resolveSymbol(objectExpr, visitor)
                    when (resolvedVar) {
                        is org.codehaus.groovy.ast.Variable -> resolvedVar.type
                        is org.codehaus.groovy.ast.FieldNode -> resolvedVar.type
                        is org.codehaus.groovy.ast.PropertyNode -> resolvedVar.type
                        else -> null
                    }
                }
            }
        }
        is org.codehaus.groovy.ast.expr.MethodCallExpression -> {
            // Method call - try to determine return type (simplified)
            // This would require more sophisticated type inference
            null
        }
        else -> null
    }

    // If we have a target class, look for the field/property in it
    return targetClass?.let { classNode ->
        symbolTable.findFieldDeclaration(classNode, propertyName)
            ?: classNode.getField(propertyName)
            ?: classNode.getProperty(propertyName)
    }
}

/**
 * Check if this node represents a symbol that can provide hover information.
 */
fun ASTNode.isHoverable(): Boolean = when (this) {
    is MethodNode -> true
    is Variable -> true
    is ClassNode -> true
    is FieldNode -> true
    is PropertyNode -> true
    is Parameter -> true
    is VariableExpression -> true
    is ConstantExpression -> true
    is MethodCallExpression -> true
    is DeclarationExpression -> true
    is BinaryExpression -> true
    is ClosureExpression -> true
    is GStringExpression -> true
    is ImportNode -> true
    is PackageNode -> true
    is AnnotationNode -> true
    is AnnotationConstantExpression -> true
    else -> false
}
