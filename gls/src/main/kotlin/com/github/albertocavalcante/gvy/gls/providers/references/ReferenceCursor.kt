// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.utils.ParameterOrigins
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import io.github.oshai.kotlinlogging.KLogger
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import java.net.URI

/**
 * Context for reference resolution.
 */
internal class ReferenceCursor(
    val documentUri: URI,
    val compiled: CompiledDocumentSnapshot,
    val source: TextIndex,
    val targetNode: ASTNode,
) {
    val visitor get() = compiled.parseResult.astModel
    val symbolTable get() = compiled.parseResult.symbolTable
    val parameterOrigins = ParameterOrigins(visitor, documentUri)
}

internal fun referenceCursor(
    documentUri: URI,
    position: Position,
    compiled: CompiledDocumentSnapshot,
): ReferenceCursor? {
    val source = TextIndex(compiled.sourceText)
    val groovyPosition = source.codePointPosition(position)?.toGroovyPosition() ?: return null
    return compiled.parseResult.astModel.getNodeAt(documentUri, groovyPosition)
        ?.takeIf { it.isReferenceableSymbol() }
        ?.let { ReferenceCursor(documentUri, compiled, source, it) }
}

/**
 * Resolve the target node to its definition.
 */
internal fun ReferenceCursor.definition(logger: KLogger): ASTNode? {
    // INSIGHT from fork-groovy-language-server: Get definition directly from the target node
    // For VariableExpression, this should return accessedVariable which unifies all references
    val definition = targetNode.resolveToDefinition(visitor, symbolTable, strict = false)

    // CRITICAL FIX: If position finder returned ClassNode but we have a VariableExpression nearby,
    // we might need to find the actual VariableExpression we're looking for
    val adjustedDefinition = when {
        // If we get a ClassNode but the context suggests we're looking for a variable
        targetNode is ClassNode &&
            definition is ClassNode -> {
            // Try to find a VariableExpression at the same position that might be the real target
            val targetLineNumber = targetNode.lineNumber
            val targetColumnNumber = targetNode.columnNumber

            if (targetLineNumber > 0 && targetColumnNumber > 0) {
                // Look for VariableExpression nodes at or near this position
                val allNodes = visitor.getAllNodes()
                val variableAtPosition = allNodes
                    .filterIsInstance<VariableExpression>()
                    .find { varNode ->
                        varNode.lineNumber > 0 && varNode.columnNumber > 0 &&
                            varNode.lineNumber == targetLineNumber &&
                            kotlin.math.abs(varNode.columnNumber - targetColumnNumber) <= 2
                    }

                // If we found a VariableExpression, use its definition instead
                variableAtPosition?.resolveToDefinition(
                    visitor,
                    symbolTable,
                    strict = false,
                ) ?: definition
            } else {
                definition
            }
        }

        else -> definition
    }

    if (adjustedDefinition == null) {
        logger.debug { "Could not resolve definition for node" }
        return null
    }
    return if (adjustedDefinition is Parameter) {
        sourceParameterOrIdentity(adjustedDefinition)
    } else {
        adjustedDefinition
    }
}

/** Public reads can follow a compiler-owned binding even when no source declaration can be proved. */
private fun ReferenceCursor.sourceParameterOrIdentity(parameter: Parameter): Parameter = try {
    parameterOrigins.source(parameter)
} catch (e: IllegalStateException) {
    rethrowIfCancellationOrError(e)
    parameter
}

/**
 * Check if this node represents a referenceable symbol.
 * Groups related node types to reduce cyclomatic complexity.
 */
private fun ASTNode.isReferenceableSymbol(): Boolean {
    val referenceableTypes = setOf(
        // Variable-related nodes
        VariableExpression::class,
        DeclarationExpression::class,
        Parameter::class,
        Variable::class,
        // Method and field nodes
        MethodCallExpression::class,
        MethodNode::class,
        FieldNode::class,
        PropertyNode::class,
        PropertyExpression::class,
        // Class-related nodes
        ClassNode::class,
        ClassExpression::class,
        ConstructorCallExpression::class,
        // Import nodes
        ImportNode::class,
    )
    // Nested classes are InnerClassNode, a ClassNode subclass the exact-class set would miss.
    return referenceableTypes.contains(this::class) || this is ClassNode
}
