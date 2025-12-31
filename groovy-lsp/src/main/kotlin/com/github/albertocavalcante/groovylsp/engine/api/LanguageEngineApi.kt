package com.github.albertocavalcante.groovylsp.engine.api

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.eclipse.lsp4j.HoverParams
import java.util.concurrent.CompletableFuture

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
    val result: ParseResultMetadata // Need to define this or reuse existing ParseResult wrapper?

    // RETHINK: Reusing existing ParseResult might be safer for now as it holds generic lists of diagnostics
    // val parseResult: ParseResult

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
 */
// TODO(#514): Convert to suspend function instead of CompletableFuture.
//   See: https://github.com/albertocavalcante/gvy/issues/514
// TODO(#515): Import org.eclipse.lsp4j.Hover and use simple name.
//   See: https://github.com/albertocavalcante/gvy/issues/515
interface HoverProvider {
    fun getHover(params: HoverParams): CompletableFuture<org.eclipse.lsp4j.Hover>
}

// TODO: Move/Refactor ParseResult usage. For now, we might need a simplified metadata interface
// TODO(#515): Import org.eclipse.lsp4j.Diagnostic and use simple name.
//   See: https://github.com/albertocavalcante/gvy/issues/515
interface ParseResultMetadata {
    val isSuccess: Boolean
    val diagnostics: List<org.eclipse.lsp4j.Diagnostic>
}
