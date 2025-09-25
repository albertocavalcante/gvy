package com.github.albertocavalcante.groovylsp.dsl

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * DSL for creating LSP Diagnostic objects in an immutable, type-safe manner.
 */

/**
 * Top-level function to create a single diagnostic.
 */
fun diagnostic(init: DiagnosticBuilder.() -> Unit): Diagnostic = DiagnosticBuilder().apply(init).build()

/**
 * Top-level function to create a list of diagnostics.
 */
fun diagnostics(init: DiagnosticsBuilder.() -> Unit): List<Diagnostic> = DiagnosticsBuilder().apply(init).build()

/**
 * Builder for a single diagnostic with sensible defaults.
 */
@LspDslMarker
class DiagnosticBuilder : LspBuilder<Diagnostic> {
    private var range: Range = LspDslUtils.pointRange(0, 0)
    private var severity: DiagnosticSeverity = DiagnosticSeverity.Error
    private var message: String = ""
    private var source: String = "groovy-lsp"
    private var code: Either<String, Int>? = null

    /**
     * Set the range for this diagnostic.
     */
    fun range(range: Range) {
        this.range = range
    }

    /**
     * Set severity to Error.
     */
    fun error(message: String) {
        this.severity = DiagnosticSeverity.Error
        this.message = message
    }

    /**
     * Set severity to Warning.
     */
    fun warning(message: String) {
        this.severity = DiagnosticSeverity.Warning
        this.message = message
    }

    /**
     * Set severity to Information.
     */
    fun info(message: String) {
        this.severity = DiagnosticSeverity.Information
        this.message = message
    }

    /**
     * Set severity to Hint.
     */
    fun hint(message: String) {
        this.severity = DiagnosticSeverity.Hint
        this.message = message
    }

    /**
     * Set the message directly.
     */
    fun message(message: String) {
        this.message = message
    }

    /**
     * Set the source (defaults to "groovy-lsp").
     */
    fun source(source: String) {
        this.source = source
    }

    /**
     * Set a string code for the diagnostic.
     */
    fun code(code: String) {
        this.code = Either.forLeft(code)
    }

    /**
     * Set a numeric code for the diagnostic.
     */
    fun code(code: Int) {
        this.code = Either.forRight(code)
    }

    // Delegate convenience methods to RangeBuilder for backward compatibility

    /**
     * Set the range using line and character positions.
     */
    fun range(line: Int, startChar: Int, endChar: Int) {
        this.range = RangeBuilder.range(line, startChar, endChar)
    }

    /**
     * Set the range spanning multiple lines.
     */
    fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) {
        this.range = RangeBuilder.range(startLine, startChar, endLine, endChar)
    }

    /**
     * Set a point range (zero-width) at a specific position.
     */
    fun at(line: Int, character: Int) {
        this.range = RangeBuilder.at(line, character)
    }

    /**
     * Set range to cover an entire line.
     */
    fun line(lineNumber: Int) {
        this.range = RangeBuilder.line(lineNumber)
    }

    // Delegate convenience methods to semantic builders for backward compatibility

    /**
     * Common syntax error diagnostic.
     */
    fun syntaxError(line: Int, message: String) {
        val syntaxDiag = SyntaxErrorBuilder().build(line, message)
        this.range = syntaxDiag.range
        this.severity = syntaxDiag.severity
        this.message = syntaxDiag.message
        this.source = syntaxDiag.source
        this.code = syntaxDiag.code
    }

    /**
     * Common type error diagnostic.
     */
    fun typeError(line: Int, startChar: Int, endChar: Int, message: String) {
        val typeDiag = TypeErrorBuilder().build(line, startChar, endChar, message)
        this.range = typeDiag.range
        this.severity = typeDiag.severity
        this.message = typeDiag.message
        this.source = typeDiag.source
        this.code = typeDiag.code
    }

    override fun build(): Diagnostic = Diagnostic().apply {
        this.range = this@DiagnosticBuilder.range
        this.severity = this@DiagnosticBuilder.severity
        this.message = this@DiagnosticBuilder.message
        this.source = this@DiagnosticBuilder.source
        this.code = this@DiagnosticBuilder.code
    }
}

/**
 * Helper builders for diagnostic creation.
 */

/**
 * Builder for range creation with various convenience methods.
 */
@LspDslMarker
object RangeBuilder {
    /**
     * Create range using line and character positions.
     */
    fun range(line: Int, startChar: Int, endChar: Int): Range = LspDslUtils.range(line, startChar, endChar)

    /**
     * Create range spanning multiple lines.
     */
    fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Range =
        LspDslUtils.range(startLine, startChar, endLine, endChar)

    /**
     * Create a point range (zero-width) at a specific position.
     */
    fun at(line: Int, character: Int): Range = LspDslUtils.pointRange(line, character)

    /**
     * Create range to cover an entire line.
     */
    fun line(lineNumber: Int): Range = LspDslUtils.lineRange(lineNumber)
}

/**
 * Builder for syntax error diagnostics.
 */
@LspDslMarker
class SyntaxErrorBuilder {
    fun build(line: Int, message: String): Diagnostic = diagnostic {
        range(RangeBuilder.line(line))
        error(message)
        source("groovy-lsp")
        code("syntax-error")
    }
}

/**
 * Builder for type error diagnostics.
 */
@LspDslMarker
class TypeErrorBuilder {
    fun build(line: Int, startChar: Int, endChar: Int, message: String): Diagnostic = diagnostic {
        range(RangeBuilder.range(line, startChar, endChar))
        error(message)
        source("groovy-lsp")
        code("type-error")
    }
}

/**
 * Builder for multiple diagnostics.
 */
@LspDslMarker
class DiagnosticsBuilder : LspBuilder<List<Diagnostic>> {
    private val diagnostics = mutableListOf<Diagnostic>()

    /**
     * Add a diagnostic using the diagnostic DSL.
     */
    fun diagnostic(init: DiagnosticBuilder.() -> Unit) {
        diagnostics.add(DiagnosticBuilder().apply(init).build())
    }

    /**
     * Add a pre-built diagnostic.
     */
    fun add(diagnostic: Diagnostic) {
        diagnostics.add(diagnostic)
    }

    /**
     * Add multiple pre-built diagnostics.
     */
    fun addAll(diagnostics: List<Diagnostic>) {
        this.diagnostics.addAll(diagnostics)
    }

    /**
     * Shorthand for syntax error.
     */
    fun syntaxError(line: Int, message: String) {
        diagnostics.add(SyntaxErrorBuilder().build(line, message))
    }

    /**
     * Shorthand for type error.
     */
    fun typeError(line: Int, startChar: Int, endChar: Int, message: String) {
        diagnostics.add(TypeErrorBuilder().build(line, startChar, endChar, message))
    }

    override fun build(): List<Diagnostic> = diagnostics.toList()
}
