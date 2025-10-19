package com.github.albertocavalcante.groovylsp.codenarc

import com.github.albertocavalcante.groovylsp.ast.CoordinateSystem
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
 *
 * CRITICAL FIX: This implementation fixes the diagnostic triplication bug by ensuring
 * violations are only processed from leaf nodes in the CodeNarc Results hierarchy.
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

        // Convert results to LSP diagnostics with triplication fix
        val diagnostics = convertResultsToDiagnosticsFixed(results, sourceCode)

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
     * CRITICAL BUG FIX: Converts CodeNarc Results to LSP Diagnostics without triplication.
     *
     * The original bug was that violations were being processed multiple times because
     * CodeNarc Results form a hierarchy (DirectoryResults > FileResults > violations).
     * The fix is to ONLY process violations from leaf nodes (nodes with no children).
     */
    private fun convertResultsToDiagnosticsFixed(results: Results, sourceCode: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val sourceLines = sourceCode.lines()

        // Collect violations only from leaf nodes to prevent triplication
        collectViolationsFromLeafNodes(results, diagnostics, sourceLines)

        logger.debug("Total unique diagnostics generated: ${diagnostics.size}")
        return diagnostics
    }

    /**
     * THE FIX: Recursively traverse the Results hierarchy and collect violations
     * ONLY from leaf nodes (nodes with no children).
     *
     * This prevents the triplication bug where violations were being collected
     * from both parent and child nodes in the hierarchy.
     */
    private fun collectViolationsFromLeafNodes(
        results: Results,
        diagnostics: MutableList<Diagnostic>,
        sourceLines: List<String>,
    ) {
        logger.debug(
            "Processing ${results.javaClass.simpleName}: " +
                "${results.violations.size} violations, " +
                "${results.children.size} children",
        )

        if (results.children.isEmpty()) {
            processLeafViolations(results.violations, diagnostics, sourceLines)
            return
        }

        logger.debug("Recursing into ${results.children.size} child results")
        results.children
            .filterIsInstance<Results>()
            .forEach { childResult ->
                collectViolationsFromLeafNodes(childResult, diagnostics, sourceLines)
            }
    }

    private fun processLeafViolations(
        violations: Collection<Violation>,
        diagnostics: MutableList<Diagnostic>,
        sourceLines: List<String>,
    ) {
        logger.debug("Processing leaf node with ${violations.size} violations")
        violations.filterIsInstance<Violation>().forEach { violation ->
            val diagnostic = convertViolationToDiagnostic(violation, sourceLines)
            if (diagnostic != null) {
                diagnostics.add(diagnostic)
                logger.debug("Added diagnostic for violation: ${violation.rule.name}")
            }
        }
    }

    /**
     * Converts a single CodeNarc Violation to an LSP Diagnostic using our centralized coordinate system.
     */
    private fun convertViolationToDiagnostic(violation: Violation, sourceLines: List<String>): Diagnostic? {
        return try {
            // Use CodeNarc's line number (1-based) and convert to LSP (0-based)
            val groovyLineNumber = violation.lineNumber ?: 1
            val groovyColumnNumber = 1 // CodeNarc doesn't provide column, default to start of line

            // Convert Groovy coordinates to LSP using our centralized system
            val lspPosition = CoordinateSystem.groovyToLsp(groovyLineNumber, groovyColumnNumber)

            // Validate line number bounds
            if (lspPosition.line < 0 || lspPosition.line >= sourceLines.size) {
                logger.warn("Invalid line number $groovyLineNumber for violation: ${violation.message}")
                return null
            }

            val line = sourceLines[lspPosition.line]
            val endColumn = calculateEndColumn(line, lspPosition.character, violation)

            Diagnostic().apply {
                range = Range(
                    Position(lspPosition.line, maxOf(0, lspPosition.character)),
                    Position(lspPosition.line, endColumn),
                )
                severity = mapPriorityToSeverity(violation.rule.priority)
                source = "CodeNarc"
                message = violation.message ?: "CodeNarc violation: ${violation.rule.name}"
                code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(violation.rule.name)
            }
        } catch (e: Exception) {
            logger.warn("Failed to convert violation to diagnostic: ${violation.message}", e)
            null
        }
    }

    /**
     * Calculate end column for better range highlighting.
     */
    private fun calculateEndColumn(line: String, startColumn: Int, violation: Violation): Int =
        when (violation.rule.name) {
            "TrailingWhitespace" -> {
                // Highlight trailing whitespace
                val trimmedLength = line.trimEnd().length
                if (trimmedLength < line.length) line.length else startColumn + 1
            }
            "UnnecessarySemicolon" -> {
                // Highlight semicolon
                val semicolonIndex = line.lastIndexOf(';')
                if (semicolonIndex >= 0) semicolonIndex + 1 else line.length
            }
            else -> {
                // Default: try to find end of word or highlight entire line
                if (startColumn >= 0 && startColumn < line.length) {
                    val remainingLine = line.substring(startColumn)
                    val wordEnd = remainingLine.indexOfFirst { it.isWhitespace() || it in "(){}[].,;" }
                    if (wordEnd > 0) startColumn + wordEnd else line.length
                } else {
                    line.length
                }
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
