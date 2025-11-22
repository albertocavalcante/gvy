package com.github.albertocavalcante.diagnostics.api

import org.eclipse.lsp4j.Diagnostic
import java.net.URI
import java.nio.file.Path

/**
 * Interface for providing diagnostics for Groovy source code.
 */
interface DiagnosticProvider {
    /**
     * Analyzes the given source code and returns diagnostics.
     *
     * @param source The source code to analyze
     * @param uri The URI of the source file
     * @return List of diagnostics
     */
    suspend fun analyze(source: String, uri: URI): List<Diagnostic>
}

/**
 * Configuration for diagnostics.
 */
interface DiagnosticConfiguration {
    val isEnabled: Boolean
    val propertiesFile: String?
    val autoDetectConfig: Boolean
}

/**
 * Context providing workspace information.
 */
interface WorkspaceContext {
    val root: Path?
    fun getConfiguration(): DiagnosticConfiguration
}
