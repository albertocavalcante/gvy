// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
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
 * Get the original definition node for a reference, similar to fork-groovy-language-server's getDefinition
 */
fun ASTNode.resolveToDefinition(visitor: GroovyAstModel, symbolTable: SymbolTable, strict: Boolean = true): ASTNode? =
    when (this) {
        is VariableExpression -> resolveVariableDefinition(this, visitor, symbolTable)
        is MethodCallExpression -> resolveMethodDefinition(this, symbolTable, visitor)
        is ClassNode, is ClassExpression, is ConstructorCallExpression -> resolveTypeDefinition(this)
        is AnnotationNode -> classNode
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
    symbolTable.registry.findMethodDeclarations(uri, call.method.text).firstOrNull { call.mayCall(it) }
}

/**
 * False when this call cannot reach [method]: its receiver has a known static type that is not the
 * method's class, a subclass, or an implementor of its interface/trait
 * (`"x".size()` does not call a `size()` declared in this file). Spread calls and
 * unresolved types are given the benefit of the doubt.
 * Implicit-this, `this`/`super`, and dynamically typed receivers (`def x`) may reach it.
 */
private fun MethodCallExpression.mayCall(method: MethodNode): Boolean {
    if (isImplicitThis) return true
    val receiver = objectExpression
    if (receiver is VariableExpression && (receiver.isThisExpression || receiver.isSuperExpression)) return true
    if (isSpreadSafe) return true // `list*.m()` calls m on each element, whatever the list's type
    val type = receiver.type ?: return true
    val owner = method.declaringClass?.let(::traitOrSelf) ?: return true
    if (ClassHelper.isDynamicTyped(type) || type == ClassHelper.OBJECT_TYPE) return true
    if (type.name == owner.name || type.isDerivedFromSafe(owner) || type.implementsInterfaceSafe(owner)) return true
    // Files that only reached CONVERSION leave an unresolved placeholder, named as written in source
    // (`C`, not `p.C`); compare simple names then rather than dropping the call. Only for a
    // placeholder: isResolved is false for every class compiled from source, so testing it alone
    // would match `Y.Inner` calls against `X.Inner.m`.
    return type.isUnresolvedPlaceholder() && simpleName(type) == simpleName(owner)
}

/** A trait's method bodies live in `T$Trait$Helper`; callers reach them through the trait `T`. */
private fun traitOrSelf(type: ClassNode): ClassNode =
    if (type.name.endsWith("\$Trait\$Helper")) (type as? InnerClassNode)?.outerClass ?: type else type

/** A type the compiler has not resolved yet: no redirect to a real class, not compiled from source. */
private fun ClassNode.isUnresolvedPlaceholder(): Boolean = redirect() === this && !isPrimaryClassNode && !isResolved

private fun simpleName(type: ClassNode): String = type.nameWithoutPackage.substringAfterLast('$')

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
    propertyExpr: PropertyExpression,
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
    objectExpr: Expression,
    visitor: GroovyAstModel,
    symbolTable: SymbolTable,
    context: ASTNode,
): ClassNode? = when (objectExpr) {
    is VariableExpression ->
        resolveVariableType(objectExpr, visitor, symbolTable, context)

    is MethodCallExpression ->
        null // Would require type inference
    else -> null
}

/**
 * Resolve the type of a variable expression.
 */
private fun resolveVariableType(
    varExpr: VariableExpression,
    visitor: GroovyAstModel,
    symbolTable: SymbolTable,
    context: ASTNode,
): ClassNode? = when (varExpr.name) {
    "this" -> findEnclosingClass(context, visitor)
    "super" -> findEnclosingClass(context, visitor)?.superClass
    else -> getVariableTypeFromSymbol(varExpr, symbolTable, visitor)
}

/**
 * Find the enclosing class of a given node.
 */
private fun findEnclosingClass(node: ASTNode, visitor: GroovyAstModel): ClassNode? {
    var current = visitor.getParent(node)
    var depth = 0
    while (current != null && current !is ClassNode) {
        // WORKAROUND: If we hit a MethodNode, check if it has a declaringClass
        // This handles cases where parent-child relationships don't include ClassNode -> MethodNode
        if (current is MethodNode) {
            val declaringClass = current.declaringClass
            if (declaringClass != null && !declaringClass.isScriptSafe()) {
                return declaringClass
            }
        }

        current = visitor.getParent(current)
        depth++
        if (depth > MAX_PARENT_SEARCH_DEPTH) break // Safety check
    }
    return current as? ClassNode
}

private const val MAX_PARENT_SEARCH_DEPTH = 10

/**
 * Get the type of a variable from the symbol table.
 */
private fun getVariableTypeFromSymbol(
    varExpr: VariableExpression,
    symbolTable: SymbolTable,
    visitor: GroovyAstModel,
): ClassNode? {
    val resolvedVar = symbolTable.resolveSymbol(varExpr, visitor)
    return when (resolvedVar) {
        is Variable -> resolvedVar.type
        is FieldNode -> resolvedVar.type
        is PropertyNode -> resolvedVar.type
        else -> null
    }
}

/**
 * Find a property in a class.
 */
private fun findPropertyInClass(classNode: ClassNode, propertyName: String, symbolTable: SymbolTable): ASTNode? =
    symbolTable.registry.findFieldDeclaration(classNode, propertyName)
        ?: classNode.findFieldSafe(propertyName)
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
