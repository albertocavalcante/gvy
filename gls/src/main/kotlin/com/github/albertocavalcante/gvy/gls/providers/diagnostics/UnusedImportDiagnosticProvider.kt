package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.github.oshai.kotlinlogging.KotlinLogging
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
import java.net.URI

/**
 * Diagnostic provider for unused imports.
 *
 * Uses deterministic AST-based detection via TypeUsageCollector and UnusedImportDetector.
 * Diagnostics include DiagnosticTag.Unnecessary for IDE dimming/strikethrough support.
 */
class UnusedImportDiagnosticProvider(private val compilationService: GroovyCompilationService) :
    StreamingDiagnosticProvider {

    private val logger = KotlinLogging.logger {}

    override val id: String = "unused-imports"

    // Enabled by default for better UX - this is a common feature users expect
    override val enabledByDefault: Boolean = true

    override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow {
        logger.debug { "Checking unused imports for: $uri" }

        val ast = compilationService.getAst(uri) as? ModuleNode
        if (ast == null) {
            logger.debug { "No AST available for $uri, skipping unused import check" }
            return@flow
        }

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        logger.debug { "Found ${unusedImports.size} unused imports in $uri" }

        unusedImports.forEach { importNode ->
            emit(createDiagnostic(importNode))
        }
    }

    @Suppress("CyclomaticComplexMethod") // Complexity 15 - diagnostic creation with edge cases
    private fun createDiagnostic(importNode: ImportNode): Diagnostic {
        // Defensive guard: Groovy AST uses 1-based line/column numbers; 0 or negative means "unknown position"
        if (importNode.lineNumber <= 0 || importNode.columnNumber <= 0 ||
            importNode.lastLineNumber <= 0 || importNode.lastColumnNumber <= 0
        ) {
            // Fallback to safe default position if AST has invalid coordinates
            return Diagnostic().apply {
                this.range = Range(Position(0, 0), Position(0, 0))
                this.severity = DiagnosticSeverity.Hint
                this.message = "Unused import"
                this.source = "Groovy"
                this.tags = listOf(DiagnosticTag.Unnecessary)
                this.code = Either.forLeft("unused-import")
            }
        }

        // Line numbers in Groovy AST are 1-based, LSP is 0-based
        // LSP end is EXCLUSIVE, Groovy lastColumnNumber is 1-based INCLUSIVE
        // 1-based inclusive column N equals 0-based exclusive column N (no conversion needed for end)
        val range = Range(
            Position(importNode.lineNumber - 1, importNode.columnNumber - 1),
            Position(importNode.lastLineNumber - 1, importNode.lastColumnNumber),
        )

        // Build descriptive import name for static/aliased imports
        val importName = when {
            // Static import: show class.member (e.g., "Math.PI" not just "Math")
            importNode.isStatic && !importNode.fieldName.isNullOrBlank() && !importNode.className.isNullOrBlank() ->
                "${importNode.className}.${importNode.fieldName}"
            // Aliased import: show original as alias (e.g., "ArrayList as AL")
            !importNode.alias.isNullOrBlank() && !importNode.className.isNullOrBlank() ->
                "${importNode.className} as ${importNode.alias}"
            // Regular import: show class name
            !importNode.className.isNullOrBlank() -> importNode.className
            // Package import: show package name
            !importNode.packageName.isNullOrBlank() -> importNode.packageName
            // Fallback
            else -> "import"
        }

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
