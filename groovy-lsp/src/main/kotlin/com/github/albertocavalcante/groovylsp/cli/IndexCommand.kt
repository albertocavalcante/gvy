package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import com.github.albertocavalcante.groovycommon.FileExtensions
import com.github.albertocavalcante.groovylsp.indexing.IndexFormat
import com.github.albertocavalcante.groovylsp.indexing.SymbolGenerator
import com.github.albertocavalcante.groovylsp.indexing.UnifiedIndexer
import com.github.albertocavalcante.groovylsp.indexing.lsif.LsifWriter
import com.github.albertocavalcante.groovylsp.indexing.scip.ScipWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path

class IndexCommand : CliktCommand(name = "index") {

    override fun help(context: Context) = "Generate SCIP or LSIF index for a project"

    private val format by option("--format", help = "Output format (SCIP or LSIF)")
        .enum<IndexFormat>()

    private val output by option("--output", help = "Output file path")
        .file(mustExist = false)

    private val projectRoot by argument(help = "Project root directory")
        .path(mustExist = true, canBeFile = false)
        .default(Path.of("."))

    override fun run() {
        val rootPath = projectRoot.toAbsolutePath().normalize().toString()
        val actualFormat = format ?: IndexFormat.SCIP
        val outputFile = output ?: File("index.${actualFormat.toString().lowercase()}")
        echo("Indexing $rootPath to $outputFile in $actualFormat format...")

        val fileOutputStream = FileOutputStream(outputFile)
        val writers = try {
            when (actualFormat) {
                IndexFormat.SCIP -> listOf(ScipWriter(fileOutputStream, rootPath))
                IndexFormat.LSIF -> listOf(LsifWriter(fileOutputStream, rootPath))
            }
        } catch (e: Exception) {
            try {
                fileOutputStream.close()
            } catch (_: Exception) {
                // Ignore secondary exception while closing after a construction failure
            }
            throw e
        }

        val manager = when {
            Files.exists(
                projectRoot.resolve("build.gradle"),
            ) || Files.exists(projectRoot.resolve("build.gradle.kts")) -> "gradle"

            Files.exists(projectRoot.resolve("pom.xml")) -> "maven"
            else -> "manual"
        }
        val symbolGenerator = SymbolGenerator(scheme = "scip-groovy", manager = manager)
        try {
            val indexer = UnifiedIndexer(writers, symbolGenerator)
            // Walk files - use block ensures stream is closed to prevent file handle leaks
            Files.walk(projectRoot).use { stream ->
                stream.filter { path ->
                    val file = path.toFile()
                    file.extension in FileExtensions.EXTENSIONS ||
                        file.name in FileExtensions.FILENAMES
                }
                    .forEach { path ->
                        try {
                            val relativePath = projectRoot.relativize(path).toString()
                            val content = Files.readString(path)
                            indexer.indexDocument(relativePath, content)
                        } catch (e: Exception) {
                            echo("Failed to index $path: ${e.message}", err = true)
                        }
                    }
            }
            echo("Successfully generated index at ${outputFile.absolutePath}")
        } finally {
            writers.forEach { writer ->
                try {
                    writer.close()
                } catch (e: Exception) {
                    echo("Error closing writer: ${e.message}", err = true)
                }
            }
        }
    }
}
