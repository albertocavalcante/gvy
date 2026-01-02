package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.net.URI

/**
 * Base interface for custom diagnostic rules.
 *
 * Rules analyze source code and emit diagnostics for violations.
 * This follows a simple, composable design inspired by kotlin-lsp and Metals.
 *
 * Design principles:
 * 1. Small API surface - just analyze() method
 * 2. Rule metadata (id, description, severity) for configuration
 * 3. Stateless - rules should not maintain state between invocations
 * 4. Composable - rules can be combined and filtered
 */
interface DiagnosticRule {
    /**
     * Unique identifier for this rule.
     * Used for configuration (enabling/disabling) and reporting.
     * Example: "groovy-null-safety", "jenkins-pipeline-syntax"
     */
    val id: String

    /**
     * Human-readable description of what this rule checks.
     * Example: "Detect potential null pointer exceptions"
     */
    val description: String

    /**
     * Default severity for violations of this rule.
     * Individual violations can override this.
     */
    val defaultSeverity: DiagnosticSeverity
        get() = DiagnosticSeverity.Warning

    /**
     * Whether this rule is enabled by default.
     * Set to false for experimental or expensive rules.
     */
    val enabledByDefault: Boolean
        get() = true

    /**
     * Analyze source code and return diagnostics for any violations.
     *
     * @param uri The URI of the source file
     * @param content The source code content
     * @param context Additional context for analysis (AST, symbols, etc.)
     * @return List of diagnostics representing violations
     */
    suspend fun analyze(uri: URI, content: String, context: RuleContext): List<Diagnostic>
}

/**
 * Context provided to rules during analysis.
 *
 * NOTE: Keep this minimal to maintain small API surface.
 * Rules that need more context should extract it themselves.
 */
interface RuleContext {
    /**
     * Get the parsed AST if available.
     * Returns null if parsing failed.
     */
    fun getAst(): Any?

    /**
     * Get compilation/parse errors.
     * Useful for rules that should skip analysis on syntax errors.
     */
    fun hasErrors(): Boolean
}
