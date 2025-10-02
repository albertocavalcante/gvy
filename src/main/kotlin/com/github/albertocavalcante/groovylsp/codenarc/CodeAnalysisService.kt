package com.github.albertocavalcante.groovylsp.codenarc

import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory

/**
 * Service that orchestrates CodeNarc analysis and converts results to LSP diagnostics.
 * This is the main entry point for CodeNarc integration in the LSP.
 *
 * NOTE: This is a simplified version for PR #3. The actual diagnostic conversion
 * logic will be implemented in PR #4.
 */
@Suppress("TooGenericExceptionCaught") // CodeNarc interop layer handles all analysis errors
class CodeAnalysisService(
    private val configurationProvider: ConfigurationProvider,
    private val rulesetResolver: RulesetResolver = HierarchicalRulesetResolver(),
    private val codeAnalyzer: CodeAnalyzer = DefaultCodeAnalyzer(),
) {

    companion object {
        private val logger = LoggerFactory.getLogger(CodeAnalysisService::class.java)
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

        // Execute CodeNarc analysis (placeholder for now)
        val analysisResult = codeAnalyzer.analyze(
            sourceCode = sourceCode,
            fileName = fileName,
            rulesetContent = rulesetConfig.rulesetContent,
            propertiesFile = rulesetConfig.propertiesFile,
        )

        // TODO: Convert analysis results to LSP diagnostics in PR #4
        logger.debug("CodeNarc analysis completed for $fileName (placeholder result)")
        emptyList()
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
}
