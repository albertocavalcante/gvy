package com.github.albertocavalcante.groovyparser.errors

import com.github.albertocavalcante.groovyparser.ast.types.Position
import java.net.URI

/**
 * Sealed hierarchy for all LSP-specific errors.
 * Replaces generic exceptions with type-safe error handling.
 */
sealed class GroovyParserError : Exception() {

    // Node and position related errors
    data class NodeNotFound(
        val uri: URI,
        val position: Position,
        val reason: String = "No AST node found at position",
    ) : GroovyParserError() {
        override val message = "$reason at $uri:${position.line}:${position.character}"
    }

    data class InvalidPosition(
        val uri: URI,
        val line: Int,
        val column: Int,
        val reason: String = "Invalid position coordinates",
    ) : GroovyParserError() {
        override val message = "$reason: line=$line, column=$column at $uri"
    }

    // Symbol resolution errors
    data class SymbolNotFound(
        val symbolName: String,
        val uri: URI,
        val position: Position,
        val symbolType: String = "symbol",
    ) : GroovyParserError() {
        override val message =
            "Symbol '$symbolName' ($symbolType) not found at $uri:${position.line}:${position.character}"
    }

    data class CircularReference(val symbolName: String, val referenceChain: List<String>, val uri: URI? = null) :
        GroovyParserError() {
        override val message = "Circular reference detected for '$symbolName': ${referenceChain.joinToString(" -> ")}"
    }

    // Compilation errors
    data class CompilationFailed(
        val uri: URI,
        val reason: String,
        val line: Int? = null,
        val column: Int? = null,
        override val cause: Throwable? = null,
    ) : GroovyParserError() {
        override val message = buildString {
            append("Compilation failed for $uri: $reason")
            if (line != null && column != null) {
                append(" at line $line, column $column")
            }
        }
    }

    data class SyntaxError(
        val uri: URI,
        val line: Int,
        val column: Int,
        val reason: String,
        override val cause: Throwable? = null,
    ) : GroovyParserError() {
        override val message = "Syntax error at $uri:$line:$column - $reason"
    }

    // AST processing errors
    data class AstGenerationFailed(val uri: URI, val reason: String, override val cause: Throwable? = null) :
        GroovyParserError() {
        override val message = "AST generation failed for $uri: $reason"
    }

    // Resource and cache errors
    data class ResourceExhausted(val resourceType: String, val limit: Long, val attempted: Long) : GroovyParserError() {
        override val message = "Resource exhausted: $resourceType (limit: $limit, attempted: $attempted)"
    }

    data class CacheCorruption(val cacheType: String, val reason: String, override val cause: Throwable? = null) :
        GroovyParserError() {
        override val message = "Cache corruption detected in $cacheType: $reason"
    }

    // Generic internal errors (should be rare)
    data class InternalError(val operation: String, val reason: String, override val cause: Throwable? = null) :
        GroovyParserError() {
        override val message = "Internal error during $operation: $reason"
    }
}

/**
 * Extension functions for creating common errors
 */
fun URI.nodeNotFoundError(
    position: Position,
    reason: String = "No AST node found at position",
): GroovyParserError.NodeNotFound = GroovyParserError.NodeNotFound(this, position, reason)

fun URI.invalidPositionError(
    line: Int,
    column: Int,
    reason: String = "Invalid position coordinates",
): GroovyParserError.InvalidPosition = GroovyParserError.InvalidPosition(this, line, column, reason)

fun URI.symbolNotFoundError(
    symbolName: String,
    position: Position,
    symbolType: String = "symbol",
): GroovyParserError.SymbolNotFound = GroovyParserError.SymbolNotFound(symbolName, this, position, symbolType)

fun URI.compilationFailedError(
    reason: String,
    line: Int? = null,
    column: Int? = null,
    cause: Throwable? = null,
): GroovyParserError.CompilationFailed = GroovyParserError.CompilationFailed(this, reason, line, column, cause)

fun URI.syntaxError(line: Int, column: Int, reason: String, cause: Throwable? = null): GroovyParserError.SyntaxError =
    GroovyParserError.SyntaxError(this, line, column, reason, cause)
