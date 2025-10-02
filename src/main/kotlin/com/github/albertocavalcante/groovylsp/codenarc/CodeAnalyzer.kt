package com.github.albertocavalcante.groovylsp.codenarc

import org.slf4j.LoggerFactory

/**
 * Interface for executing CodeNarc analysis on Groovy source files.
 * This provides a clean abstraction over the CodeNarc execution engine.
 *
 * NOTE: This is a simplified interface for PR #3. The actual CodeNarc integration
 * will be implemented in PR #4 which adds the diagnostic conversion logic.
 */
interface CodeAnalyzer {
    /**
     * Analyzes the given source code with the specified ruleset.
     *
     * @param sourceCode The Groovy source code to analyze
     * @param fileName The name of the file being analyzed (for reporting)
     * @param rulesetContent The CodeNarc ruleset content (Groovy DSL)
     * @param propertiesFile Optional path to CodeNarc properties file
     * @return The analysis results placeholder
     */
    fun analyze(sourceCode: String, fileName: String, rulesetContent: String, propertiesFile: String? = null): String
}

/**
 * Placeholder implementation of CodeAnalyzer for PR #3.
 * The actual CodeNarc integration will be added in PR #4.
 */
@Suppress("TooGenericExceptionCaught") // CodeNarc interop layer handles all analysis errors
class DefaultCodeAnalyzer : CodeAnalyzer {

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultCodeAnalyzer::class.java)
    }

    override fun analyze(
        sourceCode: String,
        fileName: String,
        rulesetContent: String,
        propertiesFile: String?,
    ): String {
        logger.debug("CodeNarc analysis placeholder for file: $fileName")

        // TODO: Implement actual CodeNarc analysis in PR #4
        logger.debug("Returning placeholder result for CodeNarc analysis of $fileName")
        return "placeholder-analysis-result"
    }
}

/**
 * Exception thrown when CodeNarc analysis fails.
 */
class CodeAnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)
