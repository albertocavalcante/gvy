package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.VariableExpression
import java.net.URI

/**
 * Builds symbol table data from AST visitor results.
 * Extracted from SymbolTable to provide focused building functionality.
 */
class SymbolTableBuilder(private val registry: SymbolRegistry) {

    /**
     * Build symbol table from an AST model.
     */
    fun buildFromVisitor(visitor: GroovyAstModel) {
        val allNodes = visitor.getAllNodes()

        // Group nodes by URI for efficient processing
        val nodesByUri = allNodes.groupBy { visitor.getUri(it) }

        nodesByUri.forEach { (uri, nodes) ->
            if (uri != null) {
                processNodes(nodes, uri)
            }
        }
    }

    /**
     * Process nodes for a specific URI.
     */
    private fun processNodes(nodes: List<ASTNode>, uri: URI) {
        nodes.forEach { node ->
            when (node) {
                is MethodNode -> registry.addMethodDeclaration(uri, node)
                is ClassNode -> {
                    registry.addClassDeclaration(uri, node)
                    // NOTE: Decompiled classpath nodes may throw linkage errors when resolving members.
                    runCatching { node.fields }
                        .getOrNull()
                        ?.forEach { field ->
                            registry.addFieldDeclaration(node, field.name, field)
                        }
                    runCatching { node.properties }
                        .getOrNull()
                        ?.forEach { property ->
                            registry.addFieldDeclaration(node, property.name, property)
                        }
                }

                is FieldNode -> {
                    // Field is handled by its enclosing class
                }

                is PropertyNode -> {
                    // Property is handled by its enclosing class
                }

                is ImportNode -> registry.addImportDeclaration(uri, node)
                is org.codehaus.groovy.ast.expr.DeclarationExpression -> {
                    processDeclarationExpression(node, uri)
                }

                is org.codehaus.groovy.ast.Parameter -> {
                    registry.addVariableDeclaration(uri, node)
                }
            }
        }
    }

    /**
     * Process a declaration expression to extract variable information.
     */
    private fun processDeclarationExpression(node: org.codehaus.groovy.ast.expr.DeclarationExpression, uri: URI) {
        if (node.isMultipleAssignmentDeclaration) return

        val leftExpr = node.leftExpression as? VariableExpression ?: return
        // Store the actual VariableExpression instead of creating synthetic Variable
        registry.addVariableDeclaration(uri, leftExpr)
    }
}
