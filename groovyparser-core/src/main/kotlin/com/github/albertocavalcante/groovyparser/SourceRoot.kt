package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.toList

/**
 * Represents a root directory containing Groovy source files.
 *
 * Similar to JavaParser's SourceRoot class.
 *
 * Example usage:
 * ```kotlin
 * val sourceRoot = SourceRoot(Path.of("src/main/groovy"))
 * val results = sourceRoot.tryToParse()
 *
 * results.forEach { result ->
 *     if (result.isSuccessful) {
 *         val cu = result.result.get()
 *         println("Parsed: ${cu.types.firstOrNull()?.name}")
 *     }
 * }
 * ```
 */
class SourceRoot(val root: Path) {

    private val parser = GroovyParser()
    private val parsedFiles = mutableMapOf<Path, ParseResult<CompilationUnit>>()

    /**
     * Extension filter for Groovy files.
     */
    var extensions: Set<String> = setOf("groovy", "gradle")

    /**
     * Returns the parser used by this source root.
     */
    fun getParser(): GroovyParser = parser

    /**
     * Parses all Groovy files in this source root and subdirectories.
     *
     * @return List of parse results for all found Groovy files
     */
    fun tryToParse(): List<ParseResult<CompilationUnit>> {
        val results = mutableListOf<ParseResult<CompilationUnit>>()

        findGroovyFiles(root).forEach { path ->
            val result = tryToParse(path)
            results.add(result)
        }

        return results
    }

    /**
     * Parses a specific file relative to this source root.
     *
     * @param relativePath Path relative to the source root
     * @return Parse result for the file
     */
    fun tryToParse(relativePath: Path): ParseResult<CompilationUnit> {
        val absolutePath = if (relativePath.isAbsolute) relativePath else root.resolve(relativePath)

        // Check cache
        parsedFiles[absolutePath]?.let { return it }

        // Parse and cache
        val code = Files.readString(absolutePath)
        val result = parser.parse(code)
        parsedFiles[absolutePath] = result
        return result
    }

    /**
     * Parses a specific file relative to this source root, throwing on failure.
     *
     * @param relativePath Path relative to the source root
     * @return CompilationUnit if parsing succeeds
     * @throws ParseProblemException if parsing fails
     */
    fun parse(relativePath: Path): CompilationUnit {
        val result = tryToParse(relativePath)
        if (!result.isSuccessful) {
            throw ParseProblemException(result.problems)
        }
        return result.result.get()
    }

    /**
     * Parses all Groovy files in a specific subdirectory.
     *
     * @param subdirectory Subdirectory relative to the source root
     * @return List of parse results
     */
    fun tryToParseSubdirectory(subdirectory: Path): List<ParseResult<CompilationUnit>> {
        val subPath = root.resolve(subdirectory)
        return findGroovyFiles(subPath).map { tryToParse(it) }
    }

    /**
     * Returns all successfully parsed compilation units.
     */
    fun getCompilationUnits(): List<CompilationUnit> = parsedFiles.values
        .filter { it.isSuccessful }
        .mapNotNull { it.result.orElse(null) }

    /**
     * Returns all parsed files (both successful and failed).
     */
    fun getAllParseResults(): List<ParseResult<CompilationUnit>> = parsedFiles.values.toList()

    /**
     * Returns parse results only for files that had errors.
     */
    fun getFailedParseResults(): List<ParseResult<CompilationUnit>> = parsedFiles.values.filter { !it.isSuccessful }

    /**
     * Clears the parsed files cache.
     */
    fun clearCache() {
        parsedFiles.clear()
    }

    /**
     * Returns the number of files in the cache.
     */
    fun getCacheSize(): Int = parsedFiles.size

    /**
     * Checks if a file has been parsed.
     */
    fun isParsed(relativePath: Path): Boolean {
        val absolutePath = if (relativePath.isAbsolute) relativePath else root.resolve(relativePath)
        return parsedFiles.containsKey(absolutePath)
    }

    /**
     * Finds all Groovy files in a directory.
     */
    private fun findGroovyFiles(directory: Path): List<Path> {
        if (!Files.exists(directory)) return emptyList()
        if (!Files.isDirectory(directory)) return emptyList()

        return Files.walk(directory).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { extensions.contains(it.extension) }
                .toList()
        }
    }

    companion object {
        /**
         * Creates a SourceRoot from a File.
         */
        fun from(file: File): SourceRoot = SourceRoot(file.toPath())

        /**
         * Creates a SourceRoot from a path string.
         */
        fun from(path: String): SourceRoot = SourceRoot(Path.of(path))
    }
}

/**
 * Builder for configuring and creating SourceRoot instances.
 */
class SourceRootBuilder(private val root: Path) {

    private var extensions: Set<String> = setOf("groovy", "gradle")

    /**
     * Sets the file extensions to parse.
     */
    fun withExtensions(vararg ext: String): SourceRootBuilder {
        extensions = ext.toSet()
        return this
    }

    /**
     * Builds the configured SourceRoot.
     */
    fun build(): SourceRoot {
        val sourceRoot = SourceRoot(root)
        sourceRoot.extensions = extensions
        return sourceRoot
    }
}

/**
 * Extension function to create a SourceRoot from a Path.
 */
fun Path.toSourceRoot(): SourceRoot = SourceRoot(this)

/**
 * Extension function to create a SourceRoot from a File.
 */
fun File.toSourceRoot(): SourceRoot = SourceRoot.from(this)
