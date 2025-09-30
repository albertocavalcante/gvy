package com.github.albertocavalcante.groovylsp.codenarc.quickfix

import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixContext
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixScope
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FormattingConfig
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Enhanced code action provider that uses the CodeNarc quickfix registry
 * to provide comprehensive code actions for CodeNarc diagnostics.
 */
class EnhancedCodeActionProvider(
    private val registry: CodeNarcQuickFixRegistry,
    private val compilationService: GroovyCompilationService,
) {

    private val logger = LoggerFactory.getLogger(EnhancedCodeActionProvider::class.java)

    /**
     * Provides code actions for the given parameters.
     *
     * @param params The code action parameters
     * @return List of code actions that can fix the reported issues
     */
    fun provideCodeActions(params: CodeActionParams): List<CodeAction> {
        logger.debug("EnhancedCodeActionProvider.provideCodeActions called for ${params.textDocument.uri}")

        val actions = mutableListOf<CodeAction>()

        // Filter CodeNarc diagnostics
        val allDiagnostics = params.context.diagnostics
        logger.debug("Total diagnostics: ${allDiagnostics.size}")
        allDiagnostics.forEach { diagnostic ->
            logger.debug(
                "  Diagnostic: source='${diagnostic.source}', code='${diagnostic.code}', message='${diagnostic.message}'",
            )
        }

        val codeNarcDiagnostics = allDiagnostics.filter { it.source == "codenarc" }
        logger.debug("CodeNarc diagnostics: ${codeNarcDiagnostics.size}")

        if (codeNarcDiagnostics.isEmpty()) {
            logger.debug("No CodeNarc diagnostics found, returning empty actions")
            return emptyList()
        }

        // Create fix context
        val context = createFixContext(params)
        logger.debug("Created fix context with ${context.sourceLines.size} source lines")

        // Generate individual fixes
        val individualActions = generateIndividualActions(codeNarcDiagnostics, context)
        logger.debug("Generated ${individualActions.size} individual actions")
        actions.addAll(individualActions)

        // Generate fix-all action if we have CodeNarc diagnostics
        if (codeNarcDiagnostics.isNotEmpty()) {
            val fixAllAction = generateFixAllAction(codeNarcDiagnostics, context)
            fixAllAction?.let {
                logger.debug("Generated fix-all action")
                actions.add(it)
            }
        }

        logger.debug("Returning ${actions.size} total actions")
        return actions
    }

    /**
     * Generates individual code actions for each diagnostic.
     */
    private fun generateIndividualActions(diagnostics: List<Diagnostic>, context: FixContext): List<CodeAction> {
        val actions = mutableListOf<CodeAction>()

        for (diagnostic in diagnostics) {
            val ruleName = diagnostic.code.left
            logger.debug("Processing diagnostic for rule: $ruleName")

            val fixers = registry.getFixers(ruleName)
            logger.debug("Found ${fixers.size} fixers for rule $ruleName")

            for (fixer in fixers) {
                try {
                    logger.debug("Trying fixer ${fixer::class.simpleName} for rule $ruleName")

                    if (fixer.canFix(diagnostic, context)) {
                        logger.debug("Fixer ${fixer::class.simpleName} can fix the diagnostic")
                        val action = fixer.computeAction(diagnostic, context)
                        if (action != null) {
                            logger.debug("Fixer ${fixer::class.simpleName} generated action: ${action.title}")
                            actions.add(action)
                        } else {
                            logger.debug("Fixer ${fixer::class.simpleName} returned null action")
                        }
                    } else {
                        logger.debug("Fixer ${fixer::class.simpleName} cannot fix this diagnostic")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to compute action for rule $ruleName with fixer ${fixer::class.simpleName}", e)
                }
            }
        }

        // Sort by fixer priority (lower number = higher priority)
        return actions.sortedBy { action ->
            // Find the fixer that created this action by matching the title pattern
            val ruleName = diagnostics.find { it in (action.diagnostics ?: emptyList()) }?.code?.left
            if (ruleName != null) {
                registry.getFixers(ruleName).minOfOrNull { it.metadata.priority } ?: 5
            } else {
                5
            }
        }
    }

    /**
     * Generates a fix-all action that applies all available fixes.
     */
    private fun generateFixAllAction(diagnostics: List<Diagnostic>, context: FixContext): CodeAction? {
        try {
            val allEdits = mutableListOf<org.eclipse.lsp4j.TextEdit>()

            // Group diagnostics by rule and let each fixer handle its own
            val diagnosticsByRule = diagnostics.groupBy { it.code.left }

            for ((ruleName, ruleDiagnostics) in diagnosticsByRule) {
                val fixers = registry.getFixers(ruleName)
                for (fixer in fixers) {
                    val action = fixer.computeFixAllAction(ruleDiagnostics, context)
                    action?.edit?.changes?.get(context.uri)?.let { edits ->
                        allEdits.addAll(edits)
                    }
                }
            }

            if (allEdits.isEmpty()) return null

            return CodeAction().apply {
                title = "Fix all CodeNarc issues"
                kind = CodeActionKind.SourceFixAll
                this.diagnostics = diagnostics
                edit = org.eclipse.lsp4j.WorkspaceEdit().apply {
                    changes = mapOf(context.uri to allEdits)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to compute fix-all action", e)
            return null
        }
    }

    /**
     * Creates a fix context from the code action parameters.
     */
    private fun createFixContext(params: CodeActionParams): FixContext {
        val uri = params.textDocument.uri
        val sourceLines = getSourceLines(uri)

        return FixContext(
            uri = uri,
            document = params.textDocument,
            sourceLines = sourceLines,
            compilationUnit = getCompilationUnit(uri),
            astCache = null, // TODO: Implement AST cache
            formattingConfig = FormattingConfig(), // TODO: Get from configuration
            scope = FixScope.FILE,
        )
    }

    /**
     * Gets the source lines for the given URI.
     */
    private fun getSourceLines(uri: String): List<String> {
        return try {
            // Try to get from compilation service first
            val sourceFromCompilation = getSourceLinesFromCompilation(uri)
            if (sourceFromCompilation.isNotEmpty()) {
                logger.debug("Got ${sourceFromCompilation.size} source lines from compilation service for $uri")
                return sourceFromCompilation
            }

            // Fallback: read from file directly
            val path = Paths.get(java.net.URI(uri))
            if (Files.exists(path)) {
                val lines = Files.readAllLines(path)
                logger.debug("Got ${lines.size} source lines from file for $uri")
                lines
            } else {
                logger.warn("File does not exist for URI: $uri")
                emptyList()
            }
        } catch (e: Exception) {
            logger.warn("Could not read source lines for $uri: ${e.message}")
            emptyList()
        }
    }

    /**
     * Attempts to get source lines from the compilation service.
     */
    private fun getSourceLinesFromCompilation(uri: String): List<String> = try {
        // TODO: Implement once we have access to document content from compilation service
        // For now, return empty to use file reading fallback
        emptyList()
    } catch (e: Exception) {
        logger.debug("Could not get source lines from compilation service for $uri", e)
        emptyList()
    }

    /**
     * Gets the compilation unit for the given URI.
     */
    private fun getCompilationUnit(uri: String): org.codehaus.groovy.control.SourceUnit? = try {
        // TODO: Get from compilation service
        null
    } catch (e: Exception) {
        logger.debug("Could not get compilation unit for $uri", e)
        null
    }
}
