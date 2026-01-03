package com.github.albertocavalcante.groovylsp.providers.diagnostics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Diagnostic provider for unused imports.
 *
 * NOTE: Performance concern - kotlin-lsp disables unused import inspection as "too slow"
 * This provider is disabled by default (opt-in via configuration).
 *
 * TODO: Implement full type usage analysis using TypeCollector
 * TODO: Benchmark on realistic files and optimize if needed
 */
class UnusedImportDiagnosticProvider : StreamingDiagnosticProvider {
    private val logger = LoggerFactory.getLogger(UnusedImportDiagnosticProvider::class.java)

    override val id: String = "unused-imports"

    // NOTE: Following kotlin-lsp approach - disabled by default due to performance concerns
    override val enabledByDefault: Boolean = false

    override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> {
        // TODO: Implement AST parsing and type usage analysis
        // For now, return empty flow until we integrate with compilation service
        logger.debug("UnusedImportDiagnosticProvider called for $uri (not yet implemented)")
        return emptyFlow()
    }

    /**
     * Internal check method for testability.
     * NOTE: This is a heuristic approach - checks if import alias is referenced in code
     * TODO: Implement proper type usage analysis with full semantic understanding
     */
    fun checkUnusedImports(moduleNode: ModuleNode, usedTypeNames: Set<String>): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()

        // Check regular imports
        for (importNode in moduleNode.imports) {
            // Using alias because that's what's used in the code
            if (!usedTypeNames.contains(importNode.alias)) {
                diagnostics.add(createDiagnostic(importNode))
            }
        }

        return diagnostics
    }

    private fun createDiagnostic(importNode: ImportNode): Diagnostic {
        // Line numbers in Groovy AST are 1-based, LSP is 0-based
        val range = Range(
            Position(importNode.lineNumber - 1, importNode.columnNumber - 1),
            Position(importNode.lastLineNumber - 1, importNode.lastColumnNumber - 1),
        )
        return Diagnostic().apply {
            this.range = range
            this.severity = DiagnosticSeverity.Hint
            this.message = "Unused import: ${importNode.className}"
            this.source = "Groovy"
            this.tags = listOf(DiagnosticTag.Unnecessary)
            this.code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("unused-import")
        }
    }
}
