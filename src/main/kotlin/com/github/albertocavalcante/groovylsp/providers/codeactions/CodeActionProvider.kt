package com.github.albertocavalcante.groovylsp.providers.codeactions

import com.github.albertocavalcante.groovylsp.codenarc.quickfix.CodeNarcQuickFixRegistry
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.EnhancedCodeActionProvider
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.TrailingWhitespaceFixer
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.UnnecessarySemicolonFixer
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.slf4j.LoggerFactory

/**
 * Provides code actions (quick fixes) for Groovy source code.
 * Enhanced implementation with CodeNarc quickfix support.
 */
class CodeActionProvider(private val compilationService: GroovyCompilationService) {

    private val logger = LoggerFactory.getLogger(CodeActionProvider::class.java)

    // CodeNarc quickfix infrastructure
    private val codeNarcRegistry = CodeNarcQuickFixRegistry().apply {
        // Register available fixers
        register(UnnecessarySemicolonFixer())
        register(TrailingWhitespaceFixer())
        logger.debug("Registered ${this.size()} CodeNarc fixers")
    }

    private val codeNarcProvider = EnhancedCodeActionProvider(codeNarcRegistry, compilationService)

    companion object {
        // Common import candidates for ambiguous classes
        private val IMPORT_CANDIDATES = mapOf(
            "ArrayList" to listOf("java.util.ArrayList"),
            "Date" to listOf("java.util.Date", "java.sql.Date"),
        )
    }

    /**
     * Provides code actions for the given parameters.
     * Enhanced: Uses CodeNarc quickfix system for CodeNarc diagnostics, legacy logic for others.
     */
    fun provideCodeActions(params: CodeActionParams): List<CodeAction> {
        logger.debug("CodeActionProvider.provideCodeActions called for ${params.textDocument.uri}")
        logger.debug("Total diagnostics received: ${params.context.diagnostics.size}")

        val actions = mutableListOf<CodeAction>()

        // Get CodeNarc quickfixes
        logger.debug("Calling CodeNarc provider...")
        val codeNarcActions = codeNarcProvider.provideCodeActions(params)
        logger.debug("CodeNarc provider returned ${codeNarcActions.size} actions")
        actions.addAll(codeNarcActions)

        // Handle non-CodeNarc diagnostics with legacy logic
        val nonCodeNarcDiagnostics = params.context.diagnostics.filter { it.source != "codenarc" }
        logger.debug("Non-CodeNarc diagnostics: ${nonCodeNarcDiagnostics.size}")

        val legacyActions = nonCodeNarcDiagnostics.flatMap { diagnostic ->
            when {
                isUnresolvedClassError(diagnostic) -> {
                    val className = extractClassName(diagnostic.message)
                    createImportActions(className, params.textDocument.uri, diagnostic)
                }
                isUnusedImportError(diagnostic) -> {
                    listOf(createRemoveImportAction(diagnostic, params.textDocument.uri))
                }
                else -> emptyList()
            }
        }
        logger.debug("Legacy actions: ${legacyActions.size}")
        actions.addAll(legacyActions)

        logger.debug("Total actions returned: ${actions.size}")
        return actions
    }

    private fun isUnresolvedClassError(diagnostic: org.eclipse.lsp4j.Diagnostic): Boolean =
        diagnostic.message.contains("unable to resolve class")

    private fun isUnusedImportError(diagnostic: org.eclipse.lsp4j.Diagnostic): Boolean =
        diagnostic.message.contains("is never used")

    private fun extractClassName(message: String): String {
        // Extract class name from "unable to resolve class ClassName"
        return message.substringAfter("unable to resolve class ").substringBefore(" ")
    }

    private fun createImportActions(
        className: String,
        uri: String,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
    ): List<CodeAction> {
        val candidates = IMPORT_CANDIDATES[className] ?: listOf("java.lang.$className")
        return candidates.map { fullyQualifiedName ->
            createAddImportAction(fullyQualifiedName, uri, diagnostic)
        }
    }

    private fun createAddImportAction(
        className: String,
        uri: String,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
    ): CodeAction {
        val importStatement = "import $className\n"
        val edit = WorkspaceEdit().apply {
            changes = mapOf(
                uri to listOf(
                    TextEdit(
                        Range(Position(0, 0), Position(0, 0)),
                        importStatement,
                    ),
                ),
            )
        }

        return CodeAction().apply {
            title = "Import '$className'"
            kind = CodeActionKind.QuickFix
            diagnostics = listOf(diagnostic)
            this.edit = edit
        }
    }

    private fun createRemoveImportAction(diagnostic: org.eclipse.lsp4j.Diagnostic, uri: String): CodeAction {
        val edit = WorkspaceEdit().apply {
            changes = mapOf(
                uri to listOf(
                    TextEdit(
                        Range(
                            Position(diagnostic.range.start.line, 0),
                            Position(diagnostic.range.start.line + 1, 0),
                        ),
                        "",
                    ),
                ),
            )
        }

        return CodeAction().apply {
            title = "Remove unused import"
            kind = CodeActionKind.QuickFix
            diagnostics = listOf(diagnostic)
            this.edit = edit
        }
    }
}
