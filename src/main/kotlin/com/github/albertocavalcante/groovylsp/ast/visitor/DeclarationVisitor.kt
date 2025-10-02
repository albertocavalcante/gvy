package com.github.albertocavalcante.groovylsp.ast.visitor

import com.github.albertocavalcante.groovylsp.ast.NodeRelationshipTracker
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.control.SourceUnit
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Focused visitor for declaration AST nodes.
 * Handles all declaration visitor methods (classes, methods, fields, properties)
 * to reduce complexity in the main visitor.
 */
internal class DeclarationVisitor(private val tracker: NodeRelationshipTracker) : ClassCodeVisitorSupport() {

    private val logger = LoggerFactory.getLogger(DeclarationVisitor::class.java)
    private var _sourceUnit: SourceUnit? = null
    private var currentUri: URI? = null

    public override fun getSourceUnit(): SourceUnit? = _sourceUnit

    fun setContext(sourceUnit: SourceUnit?, uri: URI) {
        this._sourceUnit = sourceUnit
        this.currentUri = uri
    }

    private fun pushNode(node: ASTNode) {
        logger.debug(
            "DeclarationVisitor: Visiting ${node.javaClass.simpleName} at ${node.lineNumber}:${node.columnNumber}",
        )
        tracker.pushNode(node, currentUri)
    }

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

    // Class-level declarations

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

    override fun visitConstructor(node: ConstructorNode) {
        pushNode(node)
        visitAnnotations(node)
        try {
            // Visit parameters
            node.parameters?.forEach { param ->
                pushNode(param)
                popNode()
            }
            super.visitConstructor(node)
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
}
