package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.DeclarationExpression
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

        // Collect all primary class nodes (actual class definitions, not type references)
        // Type references (e.g., String in "String name" or int in "int compute()") are tracked
        // by the visitor for hover/definition support but should NOT be included in document symbols.
        // Use identity-based set to handle ClassNode correctly (may override equals/hashCode).
        val primaryClassNodes = visitor.getAllClassNodes().toCollection(
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap()),
        )

        // Group nodes by URI for efficient processing
        val nodesByUri = allNodes.groupBy { visitor.getUri(it) }

        nodesByUri.forEach { (uri, nodes) ->
            if (uri != null) {
                processNodes(nodes, uri, primaryClassNodes)
            }
        }
    }

    /**
     * Process nodes for a specific URI.
     */
    private fun processNodes(nodes: List<ASTNode>, uri: URI, primaryClassNodes: Set<ClassNode>) {
        nodes.forEach { node ->
            when (node) {
                is MethodNode -> registry.addMethodDeclaration(uri, node)
                is ClassNode -> {
                    // Only add class declarations for primary class nodes (actual definitions),
                    // not type reference nodes (e.g., String, int used in field/method types).
                    // Fixes: document-symbol-basic test was reporting type references as Class symbols.
                    if (node in primaryClassNodes) {
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
                }

                is FieldNode -> {
                    // Field is handled by its enclosing class
                }

                is PropertyNode -> {
                    // Property is handled by its enclosing class
                }

                is ImportNode -> registry.addImportDeclaration(uri, node)
                is DeclarationExpression -> {
                    processDeclarationExpression(node, uri)
                }

                is Parameter -> {
                    registry.addVariableDeclaration(uri, node)
                }
            }
        }
    }

    /**
     * Process a declaration expression to extract variable information.
     */
    private fun processDeclarationExpression(node: DeclarationExpression, uri: URI) {
        if (node.isMultipleAssignmentDeclaration) return

        val leftExpr = node.leftExpression as? VariableExpression ?: return
        // TODO(#649): Store initializer (rightExpression) to avoid AST walking in consumers.
        //   See: https://github.com/albertocavalcante/gvy/issues/649
        registry.addVariableDeclaration(uri, leftExpr)
    }
}
