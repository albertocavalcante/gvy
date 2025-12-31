package com.github.albertocavalcante.groovylsp.engine.api

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams

/**
 * The Factory Interface for the Language Engine Abstract Factory pattern.
 * Responsible for creating a Language Session bound to a specific parser implementation.
 */
interface LanguageEngine {
    /**
     * Unique identifier for this engine (e.g. "native", "core", "rewrite")
     */
    val id: String

    /**
     * Creates a new session by parsing the code with the engine's specific parser.
     */
    fun createSession(request: ParseRequest): LanguageSession
}

/**
 * The Product Interface.
 * Represents a parsed unit of work and holds the feature set bound to that specific AST.
 *
 * NOTE: The actual AST is hidden inside the concrete implementation of this interface.
 * The LSP controller only sees the metadata (result) and the features.
 */
interface LanguageSession {
    /**
     * Metadata about the parse result (diagnostics, success flag, etc).
     * This is separate from the AST itself to allow agnostic consumers to report errors.
     */
    val result: ParseResultMetadata

    /**
     * The set of features available for this session.
     */
    val features: FeatureSet
}

/**
 * Container for all supported LSP features for a given session.
 */
interface FeatureSet {
    val hoverProvider: HoverProvider
    // Add other providers as we migrate them (Completion, Definition, etc.)
}

/**
 * Abstract Provider Interface for Hover.
 * Decouples the LSP service from the underlying AST.
 * Uses suspend function to avoid blocking dispatcher threads.
 */
interface HoverProvider {
    suspend fun getHover(params: HoverParams): Hover
}

/**
 * Metadata about a parse result (diagnostics, success flag).
 * Abstraction layer to decouple LSP service from parser-specific types.
 */
interface ParseResultMetadata {
    val isSuccess: Boolean
    val diagnostics: List<Diagnostic>
}
