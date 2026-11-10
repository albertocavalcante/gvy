package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.Formatter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import java.net.URI

/**
 * Main provider for code actions (quick fixes) in Groovy files.
 * Supports:
 * - Missing import resolution (unambiguous only)
 * - Formatting actions
 * - Deterministic lint fixes from CodeNarc
 */
class CodeActionProvider(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    formatter: Formatter,
) {
    private val logger = KotlinLogging.logger {}

    // Reuse action provider instances to reduce object allocation
    private val formattingAction = FormattingAction(formatter)
    private val importAction = ImportAction(compilationService)
    private val verdictMemory = SpockVerdictMemory()

    /** Drops the remembered Spock verdicts of a closed document (#1387). */
    fun forgetDocument(uri: URI) = verdictMemory.forget(uri.toString())
    private val lintFixAction = LintFixAction()

    /**
     * Provides code actions for the given context.
     * Only returns deterministic, safe actions; ambiguous or risky fixes are declined.
     */
    suspend fun provideCodeActions(params: CodeActionParams): List<CodeAction> {
        val uri = URI.create(params.textDocument.uri)
        logger.debug { "Providing code actions for ${params.textDocument.uri}" }

        // Get document content
        val snapshot = documentProvider.getSnapshot(uri) ?: run {
            logger.debug { "Document not found in provider: $uri" }
            return emptyList()
        }

        val content = snapshot.content
        val compiled = compilationService.getCompiledSnapshot(uri)
        val actions = buildList {
            if (compiled != null && compiled.sourceText == content) {
                addAll(
                    SpockTableAction.createActions(
                        SpockTableRequest(
                            params.textDocument.uri,
                            content,
                            compiled.parseResult.ast,
                            params.range,
                            params.context.only,
                            snapshot.version,
                            isPartial = !compiled.parseResult.isSuccessful,
                            verdicts = SpockVerdictMemory.Access(
                                verdictMemory,
                                compilationService.workspaceManager.fingerprintFor(uri),
                                compiled.parseResult.outcome,
                            ),
                        ),
                    ),
                )
            }

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

        logger.debug { "Returning ${actions.size} code actions for ${params.textDocument.uri}" }
        return actions
    }
}
