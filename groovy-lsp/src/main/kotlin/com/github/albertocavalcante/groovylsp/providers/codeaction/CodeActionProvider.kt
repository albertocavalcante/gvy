package com.github.albertocavalcante.groovylsp.providers.codeaction

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.services.Formatter
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Main provider for code actions (quick fixes) in Groovy files.
 * Supports:
 * - Missing import resolution (unambiguous only)
 * - Formatting actions
 * - Deterministic lint fixes from CodeNarc
 */
class CodeActionProvider(
    compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    formatter: Formatter,
) {
    private val logger = LoggerFactory.getLogger(CodeActionProvider::class.java)

    // Reuse action provider instances to reduce object allocation
    private val formattingAction = FormattingAction(formatter)
    private val importAction = ImportAction(compilationService)
    private val lintFixAction = LintFixAction()

    /**
     * Provides code actions for the given context.
     * Only returns deterministic, safe actions; ambiguous or risky fixes are declined.
     */
    suspend fun provideCodeActions(params: CodeActionParams): List<CodeAction> {
        val uri = URI.create(params.textDocument.uri)
        logger.debug("Providing code actions for ${params.textDocument.uri}")

        // Get document content
        val content = documentProvider.get(uri) ?: run {
            logger.debug("Document not found in provider: $uri")
            return emptyList()
        }

        val actions = buildList {
            // Add formatting action if available
            formattingAction.createFormattingAction(params.textDocument.uri, content)?.let(::add)

            // Add import actions for missing symbols
            addAll(
                importAction.createImportActions(
                    params.textDocument.uri,
                    params.context.diagnostics,
                    content,
                ),
            )

            // Add lint fix actions for deterministic CodeNarc fixes
            addAll(
                lintFixAction.createLintFixActions(
                    params.textDocument.uri,
                    params.context.diagnostics,
                    content,
                ),
            )
        }

        logger.debug("Returning ${actions.size} code actions for ${params.textDocument.uri}")
        return actions
    }
}
