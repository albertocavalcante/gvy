package com.github.albertocavalcante.groovylsp.codenarc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths

/**
 * Service for running CodeNarc static analysis on Groovy source code.
 *
 * This service provides asynchronous analysis capabilities and integrates with
 * the LSP diagnostic system to provide code quality feedback.
 *
 * This is now a facade that delegates to the new CodeAnalysisService architecture.
 */
@Suppress("TooGenericExceptionCaught") // CodeNarc facade layer handles all analysis errors gracefully
class CodeNarcService(private val configurationProvider: ConfigurationProvider) {

    private val analysisService = CodeAnalysisService(configurationProvider)

    companion object {
        private val logger = LoggerFactory.getLogger(CodeNarcService::class.java)
    }

    /**
     * Analyzes a string of Groovy source code using CodeNarc and returns LSP diagnostics.
     *
     * @param source The Groovy source code to analyze
     * @param uri The URI of the source file (used for configuration context)
     * @return List of LSP diagnostics representing violations found by CodeNarc
     */
    suspend fun analyzeString(source: String, uri: URI): List<Diagnostic> {
        if (source.isBlank()) {
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            try {
                val fileName = extractFileName(uri)
                logger.debug("Starting CodeNarc analysis for: $fileName")

                val diagnostics = analysisService.analyzeAndGetDiagnostics(source, fileName)

                logger.debug("CodeNarc analysis completed for $fileName: ${diagnostics.size} diagnostics")
                diagnostics
            } catch (e: Exception) {
                logger.warn("CodeNarc analysis failed for URI: $uri", e)
                emptyList()
            }
        }
    }

    /**
     * Extracts the file name from a URI for reporting purposes.
     */
    private fun extractFileName(uri: URI): String = try {
        val path = Paths.get(uri)
        path.fileName?.toString() ?: uri.toString()
    } catch (e: Exception) {
        logger.debug("Failed to extract file name from URI: $uri", e)
        uri.toString()
    }
}
