package com.github.albertocavalcante.groovylsp.ast.visitor

import com.github.albertocavalcante.groovylsp.ast.NodeRelationshipTracker
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.control.SourceUnit
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Focused visitor for statement AST nodes.
 * Handles all statement visitor methods to reduce complexity in the main visitor.
 */
internal class StatementVisitor(private val tracker: NodeRelationshipTracker) : ClassCodeVisitorSupport() {

    private val logger = LoggerFactory.getLogger(StatementVisitor::class.java)
    private var _sourceUnit: SourceUnit? = null
    private var currentUri: URI? = null

    public override fun getSourceUnit(): SourceUnit? = _sourceUnit

    fun setContext(sourceUnit: SourceUnit?, uri: URI) {
        this._sourceUnit = sourceUnit
        this.currentUri = uri
    }

    private fun pushNode(node: ASTNode) {
        logger.debug(
            "StatementVisitor: Visiting ${node.javaClass.simpleName} at ${node.lineNumber}:${node.columnNumber}",
        )
        tracker.pushNode(node, currentUri)
    }

    private fun popNode() {
        tracker.popNode()
    }

    // Basic statements

    override fun visitBlockStatement(block: org.codehaus.groovy.ast.stmt.BlockStatement) {
        pushNode(block)
        try {
            super.visitBlockStatement(block)
        } finally {
            popNode()
        }
    }

    override fun visitExpressionStatement(statement: org.codehaus.groovy.ast.stmt.ExpressionStatement) {
        pushNode(statement)
        try {
            super.visitExpressionStatement(statement)
        } finally {
            popNode()
        }
    }

    override fun visitEmptyStatement(statement: org.codehaus.groovy.ast.stmt.EmptyStatement) {
        pushNode(statement)
        try {
            super.visitEmptyStatement(statement)
        } finally {
            popNode()
        }
    }

    // Control flow statements

    override fun visitIfElse(ifElse: org.codehaus.groovy.ast.stmt.IfStatement) {
        pushNode(ifElse)
        try {
            super.visitIfElse(ifElse)
        } finally {
            popNode()
        }
    }

    override fun visitSwitch(statement: org.codehaus.groovy.ast.stmt.SwitchStatement) {
        pushNode(statement)
        try {
            super.visitSwitch(statement)
        } finally {
            popNode()
        }
    }

    override fun visitCaseStatement(statement: org.codehaus.groovy.ast.stmt.CaseStatement) {
        pushNode(statement)
        try {
            super.visitCaseStatement(statement)
        } finally {
            popNode()
        }
    }

    // Loop statements

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

    override fun visitDoWhileLoop(loop: org.codehaus.groovy.ast.stmt.DoWhileStatement) {
        pushNode(loop)
        try {
            super.visitDoWhileLoop(loop)
        } finally {
            popNode()
        }
    }

    // Jump statements

    override fun visitBreakStatement(statement: org.codehaus.groovy.ast.stmt.BreakStatement) {
        pushNode(statement)
        try {
            super.visitBreakStatement(statement)
        } finally {
            popNode()
        }
    }

    override fun visitContinueStatement(statement: org.codehaus.groovy.ast.stmt.ContinueStatement) {
        pushNode(statement)
        try {
            super.visitContinueStatement(statement)
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

    // Exception handling statements

    override fun visitTryCatchFinally(statement: org.codehaus.groovy.ast.stmt.TryCatchStatement) {
        pushNode(statement)
        try {
            super.visitTryCatchFinally(statement)
        } finally {
            popNode()
        }
    }

    override fun visitCatchStatement(statement: org.codehaus.groovy.ast.stmt.CatchStatement) {
        pushNode(statement)
        try {
            super.visitCatchStatement(statement)
        } finally {
            popNode()
        }
    }

    // Other statements

    override fun visitAssertStatement(statement: org.codehaus.groovy.ast.stmt.AssertStatement) {
        pushNode(statement)
        try {
            super.visitAssertStatement(statement)
        } finally {
            popNode()
        }
    }

    override fun visitSynchronizedStatement(statement: org.codehaus.groovy.ast.stmt.SynchronizedStatement) {
        pushNode(statement)
        try {
            super.visitSynchronizedStatement(statement)
        } finally {
            popNode()
        }
    }
}
