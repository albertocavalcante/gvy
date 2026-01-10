package com.github.albertocavalcante.groovylsp.providers.diagnostics

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Diagnostic provider for unused imports.
 *
 * Uses deterministic AST-based detection via TypeUsageCollector and UnusedImportDetector.
 * Diagnostics include DiagnosticTag.Unnecessary for IDE dimming/strikethrough support.
 */
class UnusedImportDiagnosticProvider(private val compilationService: GroovyCompilationService) :
    StreamingDiagnosticProvider {

    private val logger = LoggerFactory.getLogger(UnusedImportDiagnosticProvider::class.java)

    override val id: String = "unused-imports"

    // Enabled by default for better UX - this is a common feature users expect
    override val enabledByDefault: Boolean = true

    override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow {
        logger.debug("Checking unused imports for: {}", uri)

        val ast = compilationService.getAst(uri) as? ModuleNode
        if (ast == null) {
            logger.debug("No AST available for {}, skipping unused import check", uri)
            return@flow
        }

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        logger.debug("Found {} unused imports in {}", unusedImports.size, uri)

        unusedImports.forEach { importNode ->
            emit(createDiagnostic(importNode))
        }
    }

    private fun createDiagnostic(importNode: ImportNode): Diagnostic {
        // Line numbers in Groovy AST are 1-based, LSP is 0-based
        // LSP end is EXCLUSIVE, Groovy lastColumnNumber is 1-based INCLUSIVE
        // 1-based inclusive column N equals 0-based exclusive column N (no conversion needed for end)
        val range = Range(
            Position(importNode.lineNumber - 1, importNode.columnNumber - 1),
            Position(importNode.lastLineNumber - 1, importNode.lastColumnNumber),
        )

        val importName = importNode.className ?: importNode.packageName ?: "import"

        return Diagnostic().apply {
            this.range = range
            this.severity = DiagnosticSeverity.Hint
            this.message = "Unused import: $importName"
            this.source = "Groovy"
            this.tags = listOf(DiagnosticTag.Unnecessary)
            this.code = Either.forLeft("unused-import")
        }
    }
}
