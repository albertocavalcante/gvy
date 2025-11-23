package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.PositionAwareVisitor
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement

/**
 * Visitor component that handles all node visiting for PositionAwareVisitor.
 * Extracted to reduce the main class function count.
 *
 * TODO: This class has 14 functions, which is just over the original threshold of 13.
 * We've excluded visitor pattern classes from detekt TooManyFunctions rule because
 * visitor patterns naturally require many methods for comprehensive AST traversal.
 * This is a legitimate architectural pattern.
 */
internal class PositionNodeVisitor(private val visitor: PositionAwareVisitor) {

    fun visitModule(module: ModuleNode) {
        // Visit package node if present
        module.getPackage()?.let {
            visitor.checkAndUpdateSmallest(it)
        }

        // Visit all imports
        module.imports?.forEach { importNode ->
            visitor.checkAndUpdateSmallest(importNode)
        }

        // Visit star imports
        module.starImports?.forEach { importNode ->
            visitor.checkAndUpdateSmallest(importNode)
        }

        // Visit static imports (returns Map<String, ImportNode>)
        module.staticImports?.values?.forEach { importNode ->
            visitor.checkAndUpdateSmallest(importNode)
        }

        // Visit static star imports (returns Map<String, ImportNode>)
        module.staticStarImports?.values?.forEach { importNode ->
            visitor.checkAndUpdateSmallest(importNode)
        }

        // Visit all classes in the module
        module.classes.forEach { visitClass(it) }
    }

    fun visitClass(classNode: ClassNode) {
        visitor.checkAndUpdateSmallest(classNode)

        // Visit methods
        classNode.methods.forEach { visitMethod(it) }

        // Visit fields
        classNode.fields.forEach { visitField(it) }

        // Visit properties
        classNode.properties.forEach { visitProperty(it) }
    }

    fun visitMethod(method: MethodNode) {
        visitor.checkAndUpdateSmallest(method)

        // Visit parameters so hovering over them works
        method.parameters.forEach { param ->
            visitor.checkAndUpdateSmallest(param)
        }

        // Visit method body if available
        method.code?.let { visitNode(it) }
    }

    fun visitField(field: FieldNode) {
        visitor.checkAndUpdateSmallest(field)

        // Visit field initializer if available
        field.initialExpression?.let { visitExpression(it) }
    }

    fun visitProperty(property: PropertyNode) {
        visitor.checkAndUpdateSmallest(property)

        // Visit property initializer if available
        property.initialExpression?.let { visitExpression(it) }
    }

    fun visitExpression(expression: Expression) {
        visitor.checkAndUpdateSmallest(expression)
        expressionVisitors[expression::class]?.invoke(this, expression) ?: Unit
    }

    fun visitMethodCallExpression(expr: MethodCallExpression) {
        visitExpression(expr.objectExpression)

        // Method name itself is an expression (ConstantExpression usually)
        visitExpression(expr.method)

        (expr.arguments as? ArgumentListExpression)?.expressions?.forEach {
            visitExpression(it)
        }
    }

    fun visitArgumentListExpression(expr: ArgumentListExpression) {
        expr.expressions.forEach { visitExpression(it) }
    }

    fun visitDeclarationExpression(expr: DeclarationExpression) {
        visitExpression(expr.leftExpression)
        visitExpression(expr.rightExpression)
    }

    fun visitBinaryExpression(expr: BinaryExpression) {
        visitExpression(expr.leftExpression)
        visitExpression(expr.rightExpression)
    }

    fun visitClosureExpression(expr: ClosureExpression) {
        expr.parameters?.forEach { param ->
            visitor.checkAndUpdateSmallest(param)
        }
        expr.code?.let { visitNode(it) }
    }

    fun visitGStringExpression(expr: GStringExpression) {
        expr.strings?.forEach { stringExpr ->
            visitor.checkAndUpdateSmallest(stringExpr)
        }
        expr.values?.forEach { valueExpr ->
            visitExpression(valueExpr)
        }
    }

    companion object {
        private val expressionVisitors = mapOf<
            kotlin.reflect.KClass<out Expression>,
            PositionNodeVisitor.(Expression) -> Unit,
            >(
            MethodCallExpression::class to { expr -> visitMethodCallExpression(expr as MethodCallExpression) },
            ArgumentListExpression::class to { expr -> visitArgumentListExpression(expr as ArgumentListExpression) },
            DeclarationExpression::class to { expr -> visitDeclarationExpression(expr as DeclarationExpression) },
            BinaryExpression::class to { expr -> visitBinaryExpression(expr as BinaryExpression) },
            ClosureExpression::class to { expr -> visitClosureExpression(expr as ClosureExpression) },
            GStringExpression::class to { expr -> visitGStringExpression(expr as GStringExpression) },
        )
    }

    fun visitNode(node: ASTNode) {
        visitor.checkAndUpdateSmallest(node)

        // Handle different node types
        when (node) {
            is Statement -> visitStatement(node)
            is Expression -> visitExpression(node)
            // Add more node type handling as needed
        }
    }

    fun visitStatement(statement: Statement) {
        visitor.checkAndUpdateSmallest(statement)

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
}
