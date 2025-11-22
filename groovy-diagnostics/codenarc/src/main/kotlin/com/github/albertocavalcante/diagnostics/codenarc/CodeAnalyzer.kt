package com.github.albertocavalcante.diagnostics.codenarc

import org.codenarc.CodeNarcRunner
import org.codenarc.analyzer.FilesSourceAnalyzer
import org.codenarc.results.Results
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Interface for executing CodeNarc analysis on Groovy source files.
 * This provides a clean abstraction over the CodeNarc execution engine.
 */
interface CodeAnalyzer {
    /**
     * Analyzes the given source code with the specified ruleset.
     *
     * @param sourceCode The Groovy source code to analyze
     * @param fileName The name of the file being analyzed (for reporting)
     * @param rulesetContent The CodeNarc ruleset content (Groovy DSL)
     * @param propertiesFile Optional path to CodeNarc properties file
     * @return The analysis results
     */
    fun analyze(sourceCode: String, fileName: String, rulesetContent: String, propertiesFile: String? = null): Results
}

/**
 * Default implementation of CodeAnalyzer using CodeNarc's runner.
 */
@Suppress("TooGenericExceptionCaught") // CodeNarc interop layer handles all analysis errors
class DefaultCodeAnalyzer : CodeAnalyzer {

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultCodeAnalyzer::class.java)

        // Base temp directory
        private val baseTempDir: Path = Files.createTempDirectory("codenarc-lsp-")
            .also { it.toFile().deleteOnExit() }
    }

    override fun analyze(
        sourceCode: String,
        fileName: String,
        rulesetContent: String,
        propertiesFile: String?,
    ): Results {
        logger.debug("Starting CodeNarc analysis for file: $fileName")

        // Create a unique temp directory for this analysis to avoid collisions
        // and allow using the real filename
        val analysisId = UUID.randomUUID().toString()
        val analysisDir = Files.createDirectory(baseTempDir.resolve(analysisId))

        // Use the actual filename if possible, or a safe default
        val safeFileName = try {
            Paths.get(fileName).fileName?.toString() ?: "script.groovy"
        } catch (e: Exception) {
            "script.groovy"
        }
        val tempSourceFile = analysisDir.resolve(safeFileName)
        val tempRulesetFile = analysisDir.resolve("ruleset.groovy")

        return try {
            // Efficient write with NIO - direct UTF-8 bytes
            Files.write(tempSourceFile, sourceCode.toByteArray(StandardCharsets.UTF_8))
            Files.write(tempRulesetFile, rulesetContent.toByteArray(StandardCharsets.UTF_8))

            val runner = CodeNarcRunner().apply {
                // FilesSourceAnalyzer produces FileResults (supports getChildren())
                sourceAnalyzer = FilesSourceAnalyzer().also { analyzer ->
                    // Use reflection to set properties since Kotlin can't access Groovy property setters directly
                    analyzer.javaClass.getDeclaredField("baseDirectory").apply {
                        isAccessible = true
                        set(analyzer, analysisDir.toString())
                    }
                    analyzer.javaClass.getDeclaredField("sourceFiles").apply {
                        isAccessible = true
                        set(analyzer, arrayOf(tempSourceFile.fileName.toString()))
                    }
                }

                // Set the ruleset file
                ruleSetFiles = "file:$tempRulesetFile"

                // Set properties file if provided
                propertiesFile?.let { propFile ->
                    propertiesFilename = propFile
                    logger.debug("Using CodeNarc properties file: $propFile")
                }
            }

            // Execute analysis on the source code
            val results = runner.execute()

            logger.debug(
                "CodeNarc analysis completed for $fileName: ${results.getTotalNumberOfFiles(true)} files, " +
                    "${results.getNumberOfViolationsWithPriority(1, true)} violations",
            )

            results
        } catch (e: Exception) {
            logger.error("Failed to execute CodeNarc analysis for $fileName", e)
            throw CodeAnalysisException("CodeNarc analysis failed for $fileName", e)
        } finally {
            // Synchronous cleanup to prevent resource leaks
            try {
                // Clean up the unique analysis directory recursively
                analysisDir.toFile().deleteRecursively()
            } catch (e: Exception) {
                logger.debug("Temp file cleanup failed for $fileName: ${e.message}")
            }
        }
    }
}

/**
 * Exception thrown when CodeNarc analysis fails.
 */
class CodeAnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)
