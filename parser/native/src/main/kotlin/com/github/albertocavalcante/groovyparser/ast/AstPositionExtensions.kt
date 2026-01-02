package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.InnerClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ArrayExpression
import org.codehaus.groovy.ast.expr.AttributeExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression
import org.codehaus.groovy.ast.expr.BooleanExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ClosureListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.FieldExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.LambdaExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.MethodPointerExpression
import org.codehaus.groovy.ast.expr.MethodReferenceExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.AssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.CaseStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ContinueStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.ast.stmt.ThrowStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
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

// Position validation moved to CoordinateSystem.isValidNodePosition()
// This eliminates duplicate validation logic

/**
 * Check if this AST node contains the given position.
 * This extension function expects LSP coordinates (0-based).
 *
 * @param line LSP line number (0-based)
 * @param column LSP column number (0-based)
 * @return true if the node contains the position
 */
fun ASTNode.containsPosition(line: Int, column: Int): Boolean {
    // Delegate to CoordinateSystem for consistent and correct position checking
    return CoordinateSystem.nodeContainsPosition(this, line, column)
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
fun ASTNode.getDefinition(visitor: GroovyAstModel, symbolTable: SymbolTable): ASTNode? = when (this) {
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
fun ASTNode.resolveToDefinition(visitor: GroovyAstModel, symbolTable: SymbolTable, strict: Boolean = true): ASTNode? =
    when (this) {
        is VariableExpression -> resolveVariableDefinition(this, visitor, symbolTable)
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
private fun resolveVariableDefinition(
    expr: VariableExpression,
    visitor: GroovyAstModel,
    symbolTable: SymbolTable,
): ASTNode? {
    // First try the accessedVariable directly if it's an ASTNode
    val accessedVar = expr.accessedVariable as? ASTNode
    if (accessedVar != null) {
        return accessedVar
    }

    // Fallback to symbol table when accessedVariable is null
    // This handles local variables declared with 'def' where Groovy doesn't set accessedVariable
    return symbolTable.resolveSymbol(expr, visitor) as? ASTNode
}

/**
 * Resolve a method call expression to its definition.
 */
private fun resolveMethodDefinition(
    call: MethodCallExpression,
    symbolTable: SymbolTable,
    visitor: GroovyAstModel,
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
    visitor: GroovyAstModel,
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
    visitor: GroovyAstModel,
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
    visitor: GroovyAstModel,
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
private fun findEnclosingClass(node: ASTNode, visitor: GroovyAstModel): org.codehaus.groovy.ast.ClassNode? {
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
    visitor: GroovyAstModel,
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
    // Core declaration nodes
    MethodNode::class,
    ConstructorNode::class,
    ClassNode::class,
    InnerClassNode::class,
    FieldNode::class,
    PropertyNode::class,
    Parameter::class,
    Variable::class,
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
    // Additional expression types for comprehensive hover support
    ClassExpression::class,
    PropertyExpression::class,
    ConstructorCallExpression::class,
    StaticMethodCallExpression::class,
    AttributeExpression::class,
    FieldExpression::class,
    // Complete expression coverage - Phase 1: High Priority
    ArgumentListExpression::class,
    ArrayExpression::class,
    ListExpression::class,
    MapExpression::class,
    MapEntryExpression::class,
    RangeExpression::class,
    TernaryExpression::class,
    CastExpression::class,
    ElvisOperatorExpression::class,
    // Phase 2: Medium Priority
    MethodPointerExpression::class,
    MethodReferenceExpression::class,
    PostfixExpression::class,
    PrefixExpression::class,
    UnaryMinusExpression::class,
    UnaryPlusExpression::class,
    NotExpression::class,
    BitwiseNegationExpression::class,
    BooleanExpression::class,
    // Phase 3: Advanced/Less Common
    TupleExpression::class,
    SpreadExpression::class,
    SpreadMapExpression::class,
    NamedArgumentListExpression::class,
    LambdaExpression::class,
    ClosureListExpression::class,
    EmptyExpression::class,
    // Statement nodes (reduced priority but included for completeness)
    ExpressionStatement::class,
    BlockStatement::class,
    ReturnStatement::class,
    IfStatement::class,
    ForStatement::class,
    WhileStatement::class,
    DoWhileStatement::class,
    SwitchStatement::class,
    CaseStatement::class,
    BreakStatement::class,
    ContinueStatement::class,
    ThrowStatement::class,
    TryCatchStatement::class,
    CatchStatement::class,
    AssertStatement::class,
    SynchronizedStatement::class,
    EmptyStatement::class,
)

/**
 * Check if this node represents a symbol that can provide hover information.
 */
fun ASTNode.isHoverable(): Boolean = HOVERABLE_TYPES.contains(this::class)
