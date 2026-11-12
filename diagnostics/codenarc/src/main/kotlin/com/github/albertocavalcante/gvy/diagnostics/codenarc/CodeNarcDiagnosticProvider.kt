// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.groovyparser.ast.CoordinateSystem
import com.github.albertocavalcante.gvy.diagnostics.api.CompilationAccessor
import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticProvider
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codenarc.results.Results
import org.codenarc.rule.Violation
import org.codenarc.rule.formatting.FileEndsWithoutNewlineRule
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI
import java.nio.file.Paths

/**
 * Service that orchestrates CodeNarc analysis and converts results to LSP diagnostics.
 * This is the main entry point for CodeNarc integration in the LSP.
 *
 * CRITICAL FIX: This implementation fixes the diagnostic triplication bug by ensuring
 * violations are only processed from leaf nodes in the CodeNarc Results hierarchy.
 */
@Suppress("TooGenericExceptionCaught") // URI filename fallback; provider failures propagate.
class CodeNarcDiagnosticProvider(
    private val workspaceContext: WorkspaceContext,
    private val compilationAccessor: CompilationAccessor? = null,
    private val rulesetResolver: RulesetResolver = HierarchicalRulesetResolver(),
    private val codeAnalyzer: CodeAnalyzer = DefaultCodeAnalyzer(),
    /** Called once per ignored workspace ruleset file, e.g. to show a message to the user. */
    private val onWorkspaceRulesetIgnored: (String) -> Unit = {},
) : DiagnosticProvider {

    private val notifiedIgnored = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val LOG_FILE_NAME_LIMIT = 120
        private const val CODENARC_HIGH_PRIORITY = 1
        private const val CODENARC_MEDIUM_PRIORITY = 2
        private const val CODENARC_LOW_PRIORITY = 3
    }

    override suspend fun analyze(source: String, uri: URI): List<Diagnostic> {
        val config = workspaceContext.getConfiguration()
        if (!config.isEnabled) {
            return emptyList()
        }
        val fileName = extractFileName(uri)
        return analyzeAndGetDiagnostics(source, fileName, uri)
    }

    /**
     * Extracts the file name from a URI for reporting purposes.
     */
    private fun extractFileName(uri: URI): String = try {
        val path = Paths.get(uri)
        path.fileName?.toString() ?: uri.toString()
    } catch (e: Exception) {
        logger.debug(e) { "Failed to extract file name from URI: $uri" }
        uri.toString()
    }

    /**
     * Analyzes the given source code and returns LSP diagnostics.
     *
     * @param sourceCode The Groovy source code to analyze
     * @param fileName The name of the file being analyzed
     * @param uri The URI of the file (optional, used for AST-aware positioning)
     * @return List of LSP diagnostics representing violations found
     */
    fun analyzeAndGetDiagnostics(sourceCode: String, fileName: String, uri: URI? = null): List<Diagnostic> {
        logger.debug { "Starting CodeNarc analysis for: $fileName" }

        // Resolve the appropriate ruleset configuration
        // Per file: a Jenkinsfile and an ordinary Groovy source in one workspace get different profiles (#1236).
        val rulesetConfig = rulesetResolver.resolve(workspaceContext, uri)
        logger.debug { "Using ruleset from: ${rulesetConfig.source}" }
        rulesetConfig.ignoredWorkspaceRulesets.filter { notifiedIgnored.add(it) }.forEach(onWorkspaceRulesetIgnored)

        // Execute CodeNarc analysis directly with ruleset content
        val results = codeAnalyzer.analyze(
            sourceCode = sourceCode,
            fileName = fileName,
            rulesetContent = rulesetConfig.rulesetContent,
            propertiesFile = rulesetConfig.propertiesFile,
        )

        // Convert results to LSP diagnostics with triplication fix
        val diagnostics = convertResultsToDiagnosticsFixed(results, sourceCode, uri) +
            ignoredRulesetNotice(rulesetConfig.ignoredWorkspaceRulesets, uri)

        logger.debug {
            "CodeNarc analysis completed for ${fileName.take(LOG_FILE_NAME_LIMIT)}: ${diagnostics.size} diagnostics"
        }
        return diagnostics
    }

    /**
     * Tells the user why a workspace ruleset has no effect (it is never evaluated, for security). The notice is
     * attached only to the ignored ruleset file itself, so it does not flood the Problems panel of every file.
     */
    private fun ignoredRulesetNotice(ignored: List<String>, uri: URI?): List<Diagnostic> {
        val root = workspaceContext.root
        val analysed = uri?.let { runCatching { Paths.get(it).normalize() }.getOrNull() }
        val isIgnoredFile = root != null && analysed != null &&
            ignored.any { root.resolve(it).normalize() == analysed }
        return if (!isIgnoredFile) {
            emptyList()
        } else {
            listOf(
                Diagnostic(
                    Range(Position(0, 0), Position(0, 0)),
                    "This CodeNarc ruleset is ignored: it uses features that are not supported for security " +
                        "reasons (workspace rulesets are never evaluated as Groovy).",
                    DiagnosticSeverity.Information,
                    "CodeNarc",
                ),
            )
        }
    }

    /**
     * CRITICAL BUG FIX: Converts CodeNarc Results to LSP Diagnostics without triplication.
     *
     * The original bug was that violations were being processed multiple times because
     * CodeNarc Results form a hierarchy (DirectoryResults > FileResults > violations).
     * The fix is to ONLY process violations from leaf nodes (nodes with no children).
     */
    private fun convertResultsToDiagnosticsFixed(results: Results, sourceCode: String, uri: URI?): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val sourceLines = sourceCode.lines()

        // Get ModuleNode from compilation accessor if available (for AST-aware positioning)
        val moduleNode = uri?.let { compilationAccessor?.getAst(it) }
        val rangeCalculator = HybridRangeCalculator(moduleNode)

        // Collect violations only from leaf nodes to prevent triplication
        collectViolationsFromLeafNodes(results, diagnostics, sourceLines, rangeCalculator)

        logger.debug { "Total unique diagnostics generated: ${diagnostics.size}" }
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
        rangeCalculator: HybridRangeCalculator,
    ) {
        logger.debug {
            "Processing ${results.javaClass.simpleName}: " +
                "${results.violations.size} violations, " +
                "${results.children.size} children"
        }

        if (results.children.isEmpty()) {
            processLeafViolations(results.violations, diagnostics, sourceLines, rangeCalculator)
            return
        }

        logger.debug { "Recursing into ${results.children.size} child results" }
        results.children
            .filterIsInstance<Results>()
            .forEach { childResult ->
                collectViolationsFromLeafNodes(childResult, diagnostics, sourceLines, rangeCalculator)
            }
    }

    private fun processLeafViolations(
        violations: Collection<Violation>,
        diagnostics: MutableList<Diagnostic>,
        sourceLines: List<String>,
        rangeCalculator: HybridRangeCalculator,
    ) {
        logger.debug { "Processing leaf node with ${violations.size} violations" }
        violations.forEach { violation ->
            val diagnostic = convertViolationToDiagnostic(violation, sourceLines, rangeCalculator)
            diagnostics.add(diagnostic)
            logger.debug { "Added diagnostic for violation: ${violation.rule.name}" }
        }
    }

    /**
     * Converts a single CodeNarc Violation to an LSP Diagnostic using our centralized coordinate system.
     * Uses HybridRangeCalculator for AST-aware positioning when available.
     */
    private fun convertViolationToDiagnostic(
        violation: Violation,
        sourceLines: List<String>,
        rangeCalculator: HybridRangeCalculator,
    ): Diagnostic {
        // A missing line denotes a file-level violation. An invalid supplied line is a provider failure.
        val reportedLine = violation.lineNumber
        // Kotlin retains a trailing empty row after CR/LF; CodeNarc's readLines omits that row.
        val finalCodeNarcLine = sourceLines.lastIndex - if (sourceLines.last().isEmpty()) 1 else 0
        // CodeNarc 3.7 reports this rule's final line with a zero-based index. Other rules remain one-based.
        val groovyLineNumber = when {
            reportedLine == null -> 1
            reportedLine == finalCodeNarcLine && violation.rule is FileEndsWithoutNewlineRule -> reportedLine + 1
            else -> reportedLine
        }
        check(groovyLineNumber in 1..sourceLines.size) {
            "Invalid CodeNarc violation line $groovyLineNumber for ${sourceLines.size} source lines"
        }
        val lspPosition = CoordinateSystem.groovyToLsp(groovyLineNumber, 1)
        val line = sourceLines[lspPosition.line]
        val (startCol, endCol) = rangeCalculator.calculateRange(violation, line)

        return Diagnostic().apply {
            range = Range(Position(lspPosition.line, maxOf(0, startCol)), Position(lspPosition.line, endCol))
            severity = mapPriorityToSeverity(violation.rule.priority)
            source = "CodeNarc"
            message = violation.message ?: "CodeNarc violation: ${violation.rule.name}"
            code = Either.forLeft(violation.rule.name)
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
