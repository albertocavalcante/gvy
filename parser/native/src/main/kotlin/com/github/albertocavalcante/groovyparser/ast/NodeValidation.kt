package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.errors.GroovyParserError
import org.codehaus.groovy.ast.ASTNode

/**
 * Validation functions for AST nodes.
 * Extracted from NodeExtensions.kt to reduce function count.
 */

/**
 * Check if this AST node has valid position information.
 */
fun ASTNode.hasValidPosition(): Boolean =
    lineNumber > 0 && columnNumber > 0 && lastLineNumber > 0 && lastColumnNumber > 0

/**
 * Create a validation error for this node.
 */
fun ASTNode.validationError(message: String): GroovyParserError.InternalError = GroovyParserError.InternalError(
    "ast_validation",
    "$message for ${this::class.java.simpleName} at $lineNumber:$columnNumber",
    null,
)
