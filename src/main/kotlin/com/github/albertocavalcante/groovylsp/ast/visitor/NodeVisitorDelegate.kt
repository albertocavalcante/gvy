package com.github.albertocavalcante.groovylsp.ast.visitor

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.NodeRelationshipTracker
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
import java.net.URI

/**
 * Delegate class that handles all actual node visiting for AstVisitor.
 * This separation reduces the AstVisitor function count from 37 to 10.
 *
 * TODO: This class has 29 functions, which naturally occurs in visitor pattern implementations.
 * We've excluded visitor pattern classes from detekt TooManyFunctions rule because each AST
 * node type requires its own visit method. This is a legitimate architectural pattern.
 *
 * The visitor pattern could be further optimized with:
 * - Method dispatch tables instead of individual methods
 * - Reflection-based visiting
 * - Split into specialized visitors (expressions, statements, declarations)
 */
internal class NodeVisitorDelegate(
    @Suppress("unused") private val visitor: AstVisitor, // TODO: Remove if truly unused after refactoring
    private val tracker: NodeRelationshipTracker,
) : ClassCodeVisitorSupport() {

    private var _sourceUnit: SourceUnit? = null
    private var currentUri: URI? = null

    public override fun getSourceUnit(): SourceUnit? = _sourceUnit

    fun visitModule(module: ModuleNode, sourceUnit: SourceUnit, uri: URI) {
        this._sourceUnit = sourceUnit
        this.currentUri = uri
        tracker.clear()
        processModule(module)
    }

    private fun processModule(module: ModuleNode) {
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

        // Visit all classes in the module
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
            tracker.pushNode(node, currentUri)
        }
    }

    /**
     * Pop the current node from the stack
     */
    private fun popNode() {
        tracker.popNode()
    }

    /**
     * Visit annotations on an annotated node
     */
    override fun visitAnnotations(node: AnnotatedNode) {
        node.annotations?.forEach { annotation ->
            pushNode(annotation)
            try {
                processAnnotationMembers(annotation)
            } finally {
                popNode()
            }
        }
    }

    /**
     * Process annotation members to visit their expressions.
     */
    private fun processAnnotationMembers(annotation: org.codehaus.groovy.ast.AnnotationNode) {
        annotation.members?.forEach { (_, value) ->
            if (value is org.codehaus.groovy.ast.expr.Expression) {
                value.visit(this)
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
