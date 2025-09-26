package com.github.albertocavalcante.groovylsp.errors

import org.eclipse.lsp4j.Position
import java.net.URI

/**
 * Sealed hierarchy for all LSP-specific errors.
 * Replaces generic exceptions with type-safe error handling.
 */
sealed class LspError : Exception() {

    // Node and position related errors
    data class NodeNotFound(
        val uri: URI,
        val position: Position,
        val reason: String = "No AST node found at position",
    ) : LspError() {
        override val message = "$reason at $uri:${position.line}:${position.character}"
    }

    data class InvalidPosition(
        val uri: URI,
        val line: Int,
        val column: Int,
        val reason: String = "Invalid position coordinates",
    ) : LspError() {
        override val message = "$reason: line=$line, column=$column at $uri"
    }

    // Symbol resolution errors
    data class SymbolNotFound(
        val symbolName: String,
        val uri: URI,
        val position: Position,
        val symbolType: String = "symbol",
    ) : LspError() {
        override val message =
            "Symbol '$symbolName' ($symbolType) not found at $uri:${position.line}:${position.character}"
    }

    data class CircularReference(val symbolName: String, val referenceChain: List<String>, val uri: URI? = null) :
        LspError() {
        override val message = "Circular reference detected for '$symbolName': ${referenceChain.joinToString(" -> ")}"
    }

    // Compilation errors
    data class CompilationFailed(
        val uri: URI,
        val reason: String,
        val line: Int? = null,
        val column: Int? = null,
        override val cause: Throwable? = null,
    ) : LspError() {
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
    ) : LspError() {
        override val message = "Syntax error at $uri:$line:$column - $reason"
    }

    // AST processing errors
    data class AstGenerationFailed(val uri: URI, val reason: String, override val cause: Throwable? = null) :
        LspError() {
        override val message = "AST generation failed for $uri: $reason"
    }

    // Resource and cache errors
    data class ResourceExhausted(val resourceType: String, val limit: Long, val attempted: Long) : LspError() {
        override val message = "Resource exhausted: $resourceType (limit: $limit, attempted: $attempted)"
    }

    data class CacheCorruption(val cacheType: String, val reason: String, override val cause: Throwable? = null) :
        LspError() {
        override val message = "Cache corruption detected in $cacheType: $reason"
    }

    // Generic internal errors (should be rare)
    data class InternalError(val operation: String, val reason: String, override val cause: Throwable? = null) :
        LspError() {
        override val message = "Internal error during $operation: $reason"
    }
}

/**
 * Extension functions for creating common errors
 */
fun URI.nodeNotFoundError(position: Position, reason: String = "No AST node found at position"): LspError.NodeNotFound =
    LspError.NodeNotFound(this, position, reason)

fun URI.invalidPositionError(
    line: Int,
    column: Int,
    reason: String = "Invalid position coordinates",
): LspError.InvalidPosition = LspError.InvalidPosition(this, line, column, reason)

fun URI.symbolNotFoundError(
    symbolName: String,
    position: Position,
    symbolType: String = "symbol",
): LspError.SymbolNotFound = LspError.SymbolNotFound(symbolName, this, position, symbolType)

fun URI.compilationFailedError(
    reason: String,
    line: Int? = null,
    column: Int? = null,
    cause: Throwable? = null,
): LspError.CompilationFailed = LspError.CompilationFailed(this, reason, line, column, cause)

fun URI.syntaxErrorError(line: Int, column: Int, reason: String, cause: Throwable? = null): LspError.SyntaxError =
    LspError.SyntaxError(this, line, column, reason, cause)
