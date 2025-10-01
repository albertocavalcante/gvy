package com.github.albertocavalcante.groovylsp.codenarc

import org.codenarc.results.Results
import org.codenarc.rule.Violation
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory

/**
 * Service that orchestrates CodeNarc analysis and converts results to LSP diagnostics.
 * This is the main entry point for CodeNarc integration in the LSP.
 */
@Suppress("TooGenericExceptionCaught") // CodeNarc interop layer handles all analysis errors
class CodeAnalysisService(
    private val configurationProvider: ConfigurationProvider,
    private val rulesetResolver: RulesetResolver = HierarchicalRulesetResolver(),
    private val codeAnalyzer: CodeAnalyzer = DefaultCodeAnalyzer(),
) {

    companion object {
        private val logger = LoggerFactory.getLogger(CodeAnalysisService::class.java)
        private const val CODENARC_HIGH_PRIORITY = 1
        private const val CODENARC_MEDIUM_PRIORITY = 2
        private const val CODENARC_LOW_PRIORITY = 3
    }

    /**
     * Analyzes the given source code and returns LSP diagnostics.
     *
     * @param sourceCode The Groovy source code to analyze
     * @param fileName The name of the file being analyzed
     * @return List of LSP diagnostics representing violations found
     */
    fun analyzeAndGetDiagnostics(sourceCode: String, fileName: String): List<Diagnostic> = try {
        logger.debug("Starting CodeNarc analysis for: $fileName")

        // Get current workspace configuration
        val workspaceConfig = createWorkspaceConfiguration()

        // Resolve the appropriate ruleset configuration
        val rulesetConfig = rulesetResolver.resolve(workspaceConfig)
        logger.debug("Using ruleset from: ${rulesetConfig.source}")

        // Execute CodeNarc analysis directly with ruleset content
        val results = codeAnalyzer.analyze(
            sourceCode = sourceCode,
            fileName = fileName,
            rulesetContent = rulesetConfig.rulesetContent,
            propertiesFile = rulesetConfig.propertiesFile,
        )

        // Convert results to LSP diagnostics
        val diagnostics = convertResultsToDiagnostics(results, sourceCode)

        logger.info("CodeNarc analysis completed for $fileName: ${diagnostics.size} diagnostics")
        diagnostics
    } catch (e: Exception) {
        logger.error("Failed to analyze file: $fileName", e)
        // Return empty list on failure - LSP should continue functioning
        emptyList()
    }

    /**
     * Creates workspace configuration from the current provider state.
     */
    private fun createWorkspaceConfiguration(): WorkspaceConfiguration = WorkspaceConfiguration(
        workspaceRoot = configurationProvider.getWorkspaceRoot(),
        serverConfig = configurationProvider.getServerConfiguration(),
    )

    /**
     * Converts CodeNarc Results to LSP Diagnostics.
     */
    private fun convertResultsToDiagnostics(results: Results, sourceCode: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val sourceLines = sourceCode.lines()

        fun processResults(currentResults: Results) {
            // Add debug logging to understand the hierarchy structure
            logger.debug(
                "Processing ${currentResults.javaClass.simpleName}: " +
                    "${currentResults.violations.size} violations, " +
                    "${currentResults.children.size} children",
            )

            if (currentResults.children.isEmpty()) {
                processLeafNodeViolations(currentResults, sourceLines, diagnostics)
            } else {
                processChildResults(currentResults, ::processResults)
            }
        }

        processResults(results)
        logger.info("Total diagnostics generated: ${diagnostics.size}")
        return diagnostics
    }

    private fun processLeafNodeViolations(
        results: Results,
        sourceLines: List<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        logger.debug("Processing leaf node with ${results.violations.size} violations")
        results.violations.forEach { violation ->
            if (violation is Violation) {
                val diagnostic = convertViolationToDiagnostic(violation, sourceLines)
                if (diagnostic != null) {
                    diagnostics.add(diagnostic)
                }
            }
        }
    }

    private fun processChildResults(results: Results, processResults: (Results) -> Unit) {
        logger.debug("Skipping non-leaf node, recursing into ${results.children.size} children")
        results.children.forEach { childResult ->
            if (childResult is Results) {
                processResults(childResult)
            }
        }
    }

    /**
     * Converts a single CodeNarc Violation to an LSP Diagnostic.
     */
    private fun convertViolationToDiagnostic(violation: Violation, sourceLines: List<String>): Diagnostic? {
        return try {
            // Convert 1-based line number to 0-based for LSP
            val lineNumber = (violation.lineNumber ?: 1) - 1
            val columnNumber = maxOf(0, (violation.lineNumber ?: 1) - 1) // columnNumber doesn't exist, use line

            // Validate line number bounds
            if (lineNumber < 0 || lineNumber >= sourceLines.size) {
                logger.warn("Invalid line number ${lineNumber + 1} for violation: ${violation.message}")
                return null
            }

            val line = sourceLines[lineNumber]
            val endColumn = if (columnNumber >= 0 && columnNumber < line.length) {
                // Try to find the end of the word/token
                val remainingLine = line.substring(columnNumber)
                val wordEnd = remainingLine.indexOfFirst { it.isWhitespace() || it in "(){}[].,;" }
                if (wordEnd > 0) columnNumber + wordEnd else line.length
            } else {
                line.length
            }

            Diagnostic().apply {
                range = Range(
                    Position(lineNumber, maxOf(0, columnNumber)),
                    Position(lineNumber, endColumn),
                )
                severity = mapPriorityToSeverity(violation.rule.priority)
                source = violation.rule.name
                message = violation.message ?: "CodeNarc violation: ${violation.rule.name}"
                code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(violation.rule.name)
            }
        } catch (e: Exception) {
            logger.warn("Failed to convert violation to diagnostic: ${violation.message}", e)
            null
        }
    }

    /**
     * Maps CodeNarc rule priority to LSP diagnostic severity.
     */
    private fun mapPriorityToSeverity(priority: Int): DiagnosticSeverity = when (priority) {
        CODENARC_HIGH_PRIORITY -> DiagnosticSeverity.Error // High priority
        CODENARC_MEDIUM_PRIORITY -> DiagnosticSeverity.Warning // Medium priority
        CODENARC_LOW_PRIORITY -> DiagnosticSeverity.Information // Low priority
        else -> DiagnosticSeverity.Hint // Very low or unknown priority
    }
}
