// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.nativeapi.ParserRange
import com.github.albertocavalcante.nativeapi.SourceAnnotationIndex
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

    override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = diagnosticsFromAst(uri) {
        val parsed = compilationService.getCompiledSnapshot(uri)
            ?.takeIf { snapshot -> snapshot.sourceText == content }
            ?.parseResult ?: return@diagnosticsFromAst null
        val ast = parsed.ast ?: return@diagnosticsFromAst null
        ast to parsed.sourceAnnotations
    }

    override suspend fun provideDiagnostics(
        uri: URI,
        content: String,
        compilation: CompilationResult,
    ): Flow<Diagnostic> = diagnosticsFromAst(uri) {
        val ast = compilation.ast as? ModuleNode
        check(ast != null || !compilation.isSuccess) {
            "Completed compilation has no native AST for unused imports at $uri"
        }
        ast?.let { it to compilation.sourceAnnotations }
    }

    private fun diagnosticsFromAst(
        uri: URI,
        getAst: suspend () -> Pair<ModuleNode, SourceAnnotationIndex>?,
    ): Flow<Diagnostic> = flow {
        logger.debug { "Checking unused imports for: $uri" }

        val captured = getAst()
        if (captured == null) {
            logger.debug { "No AST available for $uri, skipping unused import check" }
            return@flow
        }

        val (ast, annotations) = captured
        val unusedImports = UnusedImportDetector.detectUnusedImports(ast, annotations)

        logger.debug { "Found ${unusedImports.size} unused imports in $uri" }

        unusedImports.forEach { importNode ->
            annotations.importRange(importNode)?.let { range -> emit(createDiagnostic(importNode, range)) }
        }
    }

    private fun createDiagnostic(importNode: ImportNode, sourceRange: ParserRange): Diagnostic {
        val range = Range(
            Position(sourceRange.start.line, sourceRange.start.character),
            Position(sourceRange.end.line, sourceRange.end.character),
        )

        // Build descriptive import name for static/aliased imports
        val importName = when {
            // Static import: show class.member (e.g., "Math.PI" not just "Math")
            importNode.isStatic &&
                !importNode.fieldName.isNullOrBlank() &&
                !importNode.className.isNullOrBlank() ->
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
