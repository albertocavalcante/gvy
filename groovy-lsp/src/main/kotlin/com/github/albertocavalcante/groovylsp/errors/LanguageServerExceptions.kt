package com.github.albertocavalcante.groovylsp.errors

import java.net.URI

/**
 * Base class for all Groovy Language Server exceptions.
 */
sealed class GroovyLspException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Compilation-related exceptions
 */
sealed class CompilationException(message: String, cause: Throwable? = null) : GroovyLspException(message, cause)

class SyntaxErrorException(uri: URI, line: Int, column: Int, message: String, cause: Throwable? = null) :
    CompilationException("Syntax error at $uri:$line:$column - $message", cause)

class CompilerConfigurationException(message: String, cause: Throwable? = null) :
    CompilationException("Compiler configuration error: $message", cause)

class AstGenerationException(val uri: URI, message: String, cause: Throwable? = null) :
    CompilationException("Failed to generate AST for $uri: $message", cause)

/**
 * Symbol resolution exceptions
 */
sealed class SymbolResolutionException(message: String, cause: Throwable? = null) : GroovyLspException(message, cause)

class SymbolNotFoundException(symbolName: String, uri: URI, line: Int, column: Int) :
    SymbolResolutionException("Symbol '$symbolName' not found at $uri:$line:$column")

class AmbiguousSymbolException(symbolName: String, candidateCount: Int, uri: URI) :
    SymbolResolutionException("Ambiguous symbol '$symbolName' at $uri ($candidateCount candidates found)")

/**
 * Position and range exceptions
 */
sealed class PositionException(message: String, cause: Throwable? = null) : GroovyLspException(message, cause)

class InvalidPositionException(uri: URI, line: Int, column: Int, reason: String) :
    PositionException("Invalid position at $uri:$line:$column - $reason")

class NodeNotFoundAtPositionException(val uri: URI, val line: Int, val column: Int) :
    PositionException("No AST node found at position $uri:$line:$column")

/**
 * Cache and resource management exceptions
 */
sealed class ResourceException(message: String, cause: Throwable? = null) : GroovyLspException(message, cause)

class CacheCorruptionException(cacheType: String, message: String, cause: Throwable? = null) :
    ResourceException("Cache corruption detected in $cacheType: $message", cause)

class ResourceExhaustionException(resourceType: String, currentUsage: Long, maxCapacity: Long) :
    ResourceException("Resource exhaustion for $resourceType: $currentUsage/$maxCapacity")

class FileAccessException(uri: URI, operation: String, cause: Throwable? = null) :
    ResourceException("Failed to $operation file $uri", cause)

/**
 * Protocol and communication exceptions
 */
sealed class ProtocolException(message: String, cause: Throwable? = null) : GroovyLspException(message, cause)

class InvalidRequestException(val method: String, val reason: String) :
    ProtocolException("Invalid LSP request for method '$method': $reason")

class UnsupportedOperationException(operation: String, reason: String) :
    ProtocolException("Unsupported operation '$operation': $reason")

class CommunicationException(message: String, cause: Throwable? = null) :
    ProtocolException("Communication error: $message", cause)

/**
 * Extension functions for creating exceptions with context
 */
fun URI.syntaxError(line: Int, column: Int, message: String, cause: Throwable? = null) =
    SyntaxErrorException(this, line, column, message, cause)

fun URI.astGenerationError(message: String, cause: Throwable? = null) = AstGenerationException(this, message, cause)

fun URI.symbolNotFound(symbolName: String, line: Int, column: Int) =
    SymbolNotFoundException(symbolName, this, line, column)

fun URI.ambiguousSymbol(symbolName: String, candidateCount: Int) =
    AmbiguousSymbolException(symbolName, candidateCount, this)

fun URI.invalidPosition(line: Int, column: Int, reason: String) = InvalidPositionException(this, line, column, reason)

fun URI.nodeNotFoundAtPosition(line: Int, column: Int) = NodeNotFoundAtPositionException(this, line, column)

fun URI.fileAccessError(operation: String, cause: Throwable? = null) = FileAccessException(this, operation, cause)
