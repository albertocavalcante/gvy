package com.github.albertocavalcante.diagnostics.codenarc

import org.codenarc.CodeNarcRunner
import org.codenarc.analyzer.FilesSourceAnalyzer
import org.codenarc.results.Results
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

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
class DefaultCodeAnalyzer internal constructor(private val rulesetFilePathProvider: (String) -> Path) : CodeAnalyzer {

    constructor() : this({ rulesetFileCache.getOrCreate(it) })

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultCodeAnalyzer::class.java)

        // Base temp directory
        private val baseTempDir: Path = Files.createTempDirectory("codenarc-lsp-")
            .also { it.toFile().deleteOnExit() }

        private val rulesetCacheDir: Path = Files.createDirectory(baseTempDir.resolve("rulesets"))
            .also { it.toFile().deleteOnExit() }

        private val rulesetFileCache = RulesetFileCache(cacheDir = rulesetCacheDir)

        private const val DEFAULT_SOURCE_FILE_NAME = "script.groovy"
    }

    override fun analyze(
        sourceCode: String,
        fileName: String,
        rulesetContent: String,
        propertiesFile: String?,
    ): Results {
        logger.debug("Starting CodeNarc analysis for file: $fileName")

        // Create a unique temp directory for this analysis to avoid collisions
        // and allow using the real filename.
        val analysisId = UUID.randomUUID().toString()
        val analysisDir = Files.createDirectory(baseTempDir.resolve(analysisId))

        val safeFileName = resolveSafeFileName(fileName)
        val tempSourceFile = analysisDir.resolve(safeFileName)

        val rulesetFile = resolveRulesetFile(analysisDir, rulesetContent)

        return try {
            // Efficient write with NIO - direct UTF-8 bytes
            Files.write(tempSourceFile, sourceCode.toByteArray(StandardCharsets.UTF_8))

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
                ruleSetFiles = rulesetFile.toUri().toString()

                // Set properties file if provided
                propertiesFile?.let { propFile ->
                    propertiesFilename = propFile
                    logger.debug("Using CodeNarc properties file: $propFile")
                }
            }

            // Execute analysis on the source code
            val results: Results
            val executionTimeMs = measureTimeMillis {
                results = runner.execute()
            }

            logger.debug(
                "CodeNarc analysis completed for $fileName: ${results.getTotalNumberOfFiles(true)} files, " +
                    "${results.getNumberOfViolationsWithPriority(1, true)} p1 violations, " +
                    "execute=${executionTimeMs}ms",
            )

            results
        } catch (e: Exception) {
            logger.error("Failed to execute CodeNarc analysis for $fileName", e)
            throw CodeAnalysisException("CodeNarc analysis failed for $fileName", e)
        } finally {
            // Synchronous cleanup to prevent resource leaks
            try {
                analysisDir.toFile().deleteRecursively()
            } catch (e: Exception) {
                logger.debug("Temp file cleanup failed for $fileName: ${e.message}")
            }
        }
    }

    internal fun resolveSafeFileName(fileName: String): String = try {
        Paths.get(fileName).fileName?.toString() ?: DEFAULT_SOURCE_FILE_NAME
    } catch (_: InvalidPathException) {
        DEFAULT_SOURCE_FILE_NAME
    }

    internal fun resolveRulesetFile(analysisDir: Path, rulesetContent: String): Path = try {
        rulesetFilePathProvider(rulesetContent)
    } catch (e: Exception) {
        // NOTE: Ruleset caching is a performance optimization; failing to cache must not break analysis.
        //       In this fallback path we create a temporary per-analysis ruleset file inside analysisDir,
        //       which is deleted along with analysisDir in the finally block after analysis completes.
        // TODO: Remove file-based ruleset loading entirely by configuring CodeNarc in-memory (if supported).
        logger.warn("Failed to cache CodeNarc ruleset file; falling back to per-analysis ruleset", e)
        analysisDir.resolve("ruleset.groovy").also { tmpRuleset ->
            Files.write(tmpRuleset, rulesetContent.toByteArray(StandardCharsets.UTF_8))
        }
    }
}

internal class RulesetFileCache(private val cacheDir: Path) {
    private val cachedFilesByHash = ConcurrentHashMap<String, Path>()

    companion object {
        // NOTE: MessageDigest.getInstance("SHA-256") is relatively expensive; reuse per-thread instances.
        // TODO: Measure impact under realistic concurrency before adding more complex pooling.
        private val sha256Digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
        private val hexFormat = HexFormat.of()
    }

    fun getOrCreate(rulesetContent: String): Path {
        // NOTE: This cache trades a small amount of disk space for significantly faster repeated analysis
        // by avoiding rewriting identical ruleset content for every CodeNarc run.
        // TODO: Replace file-based ruleset loading with a direct in-memory rule set configuration, if supported
        // by CodeNarc, to eliminate temp file IO entirely.
        val hash = sha256Hex(rulesetContent)
        return cachedFilesByHash.computeIfAbsent(hash) {
            val cachedRulesetPath = cacheDir.resolve("$hash.groovy")
            if (Files.exists(cachedRulesetPath)) {
                return@computeIfAbsent cachedRulesetPath
            }

            val tmpPath = cacheDir.resolve("$hash.${UUID.randomUUID()}.tmp")
            Files.write(tmpPath, rulesetContent.toByteArray(StandardCharsets.UTF_8))

            try {
                Files.move(tmpPath, cachedRulesetPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                // Another writer (or a previous run) created the cached file after our existence check.
                // The cache file content is keyed by hash, so it's safe to reuse the existing file.
            } catch (_: AtomicMoveNotSupportedException) {
                // NOTE: Some filesystems don't support ATOMIC_MOVE; fall back to a best-effort move.
                // TODO: Use a filesystem capability check (or single-writer lock) to avoid relying on exceptions.
                try {
                    Files.move(tmpPath, cachedRulesetPath)
                } catch (_: FileAlreadyExistsException) {
                    // Another writer created the cached file between our check and the fallback move.
                }
            } finally {
                try {
                    Files.deleteIfExists(tmpPath)
                } catch (_: Exception) {
                    // Best-effort cleanup; don't mask move failures.
                }
            }

            cachedRulesetPath
        }
    }

    private fun sha256Hex(text: String): String {
        val hashBytes = sha256Digest.get().digest(text.toByteArray(StandardCharsets.UTF_8))
        // NOTE: HexFormat formats with lowercase hex by default.
        //       The hash is an opaque identifier so casing is irrelevant.
        return hexFormat.formatHex(hashBytes)
    }
}

/**
 * Exception thrown when CodeNarc analysis fails.
 */
class CodeAnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)
