package com.github.albertocavalcante.groovylsp.ast.visitor

import com.github.albertocavalcante.groovylsp.ast.NodeRelationshipTracker
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.control.SourceUnit
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Focused visitor for expression AST nodes.
 * Handles all expression visitor methods to reduce complexity in the main visitor.
 */
internal class ExpressionVisitor(private val tracker: NodeRelationshipTracker) : ClassCodeVisitorSupport() {

    private val logger = LoggerFactory.getLogger(ExpressionVisitor::class.java)
    private var _sourceUnit: SourceUnit? = null
    private var currentUri: URI? = null

    public override fun getSourceUnit(): SourceUnit? = _sourceUnit

    fun setContext(sourceUnit: SourceUnit?, uri: URI) {
        this._sourceUnit = sourceUnit
        this.currentUri = uri
    }

    private fun pushNode(node: ASTNode) {
        logger.debug(
            "ExpressionVisitor: Visiting ${node.javaClass.simpleName} at ${node.lineNumber}:${node.columnNumber}",
        )
        tracker.pushNode(node, currentUri)
    }

    private fun popNode() {
        tracker.popNode()
    }

    // Method call expressions

    override fun visitMethodCallExpression(call: org.codehaus.groovy.ast.expr.MethodCallExpression) {
        pushNode(call)
        try {
            super.visitMethodCallExpression(call)
        } finally {
            popNode()
        }
    }

    override fun visitStaticMethodCallExpression(call: org.codehaus.groovy.ast.expr.StaticMethodCallExpression) {
        pushNode(call)
        try {
            super.visitStaticMethodCallExpression(call)
        } finally {
            popNode()
        }
    }

    override fun visitConstructorCallExpression(call: org.codehaus.groovy.ast.expr.ConstructorCallExpression) {
        pushNode(call)
        try {
            super.visitConstructorCallExpression(call)
        } finally {
            popNode()
        }
    }

    // Variable and property expressions

    override fun visitVariableExpression(expression: org.codehaus.groovy.ast.expr.VariableExpression) {
        pushNode(expression)
        try {
            super.visitVariableExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitPropertyExpression(expression: org.codehaus.groovy.ast.expr.PropertyExpression) {
        pushNode(expression)
        try {
            super.visitPropertyExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitAttributeExpression(expression: org.codehaus.groovy.ast.expr.AttributeExpression) {
        pushNode(expression)
        try {
            super.visitAttributeExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitFieldExpression(expression: org.codehaus.groovy.ast.expr.FieldExpression) {
        pushNode(expression)
        try {
            super.visitFieldExpression(expression)
        } finally {
            popNode()
        }
    }

    // Binary and unary expressions

    override fun visitBinaryExpression(expression: org.codehaus.groovy.ast.expr.BinaryExpression) {
        pushNode(expression)
        try {
            super.visitBinaryExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitUnaryMinusExpression(expression: org.codehaus.groovy.ast.expr.UnaryMinusExpression) {
        pushNode(expression)
        try {
            super.visitUnaryMinusExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitUnaryPlusExpression(expression: org.codehaus.groovy.ast.expr.UnaryPlusExpression) {
        pushNode(expression)
        try {
            super.visitUnaryPlusExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitNotExpression(expression: org.codehaus.groovy.ast.expr.NotExpression) {
        pushNode(expression)
        try {
            super.visitNotExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitPostfixExpression(expression: org.codehaus.groovy.ast.expr.PostfixExpression) {
        pushNode(expression)
        try {
            super.visitPostfixExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitPrefixExpression(expression: org.codehaus.groovy.ast.expr.PrefixExpression) {
        pushNode(expression)
        try {
            super.visitPrefixExpression(expression)
        } finally {
            popNode()
        }
    }

    // Ternary and conditional expressions

    override fun visitTernaryExpression(expression: org.codehaus.groovy.ast.expr.TernaryExpression) {
        pushNode(expression)
        try {
            super.visitTernaryExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitShortTernaryExpression(expression: org.codehaus.groovy.ast.expr.ElvisOperatorExpression) {
        pushNode(expression)
        try {
            super.visitShortTernaryExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitBooleanExpression(expression: org.codehaus.groovy.ast.expr.BooleanExpression) {
        pushNode(expression)
        try {
            super.visitBooleanExpression(expression)
        } finally {
            popNode()
        }
    }

    // Collection expressions

    override fun visitArrayExpression(expression: org.codehaus.groovy.ast.expr.ArrayExpression) {
        pushNode(expression)
        try {
            super.visitArrayExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitListExpression(expression: org.codehaus.groovy.ast.expr.ListExpression) {
        pushNode(expression)
        try {
            super.visitListExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitMapExpression(expression: org.codehaus.groovy.ast.expr.MapExpression) {
        pushNode(expression)
        try {
            super.visitMapExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitMapEntryExpression(expression: org.codehaus.groovy.ast.expr.MapEntryExpression) {
        pushNode(expression)
        try {
            super.visitMapEntryExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitTupleExpression(expression: org.codehaus.groovy.ast.expr.TupleExpression) {
        pushNode(expression)
        try {
            super.visitTupleExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitRangeExpression(expression: org.codehaus.groovy.ast.expr.RangeExpression) {
        pushNode(expression)
        try {
            super.visitRangeExpression(expression)
        } finally {
            popNode()
        }
    }

    // Spread expressions

    override fun visitSpreadExpression(expression: org.codehaus.groovy.ast.expr.SpreadExpression) {
        pushNode(expression)
        try {
            super.visitSpreadExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitSpreadMapExpression(expression: org.codehaus.groovy.ast.expr.SpreadMapExpression) {
        pushNode(expression)
        try {
            super.visitSpreadMapExpression(expression)
        } finally {
            popNode()
        }
    }

    // Other expressions

    override fun visitCastExpression(expression: org.codehaus.groovy.ast.expr.CastExpression) {
        pushNode(expression)
        try {
            super.visitCastExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitClassExpression(expression: org.codehaus.groovy.ast.expr.ClassExpression) {
        pushNode(expression)
        try {
            super.visitClassExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitClosureExpression(expression: org.codehaus.groovy.ast.expr.ClosureExpression) {
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

    override fun visitDeclarationExpression(expression: org.codehaus.groovy.ast.expr.DeclarationExpression) {
        pushNode(expression)
        try {
            super.visitDeclarationExpression(expression)
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

    override fun visitGStringExpression(expression: org.codehaus.groovy.ast.expr.GStringExpression) {
        pushNode(expression)
        try {
            super.visitGStringExpression(expression)
        } finally {
            popNode()
        }
    }

    override fun visitMethodPointerExpression(expression: org.codehaus.groovy.ast.expr.MethodPointerExpression) {
        pushNode(expression)
        try {
            super.visitMethodPointerExpression(expression)
        } finally {
            popNode()
        }
    }
}
