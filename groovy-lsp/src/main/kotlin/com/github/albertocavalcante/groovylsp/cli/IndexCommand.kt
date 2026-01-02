package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import com.github.albertocavalcante.groovycommon.FileExtensions
import com.github.albertocavalcante.groovylsp.indexing.IndexFormat
import com.github.albertocavalcante.groovylsp.indexing.UnifiedIndexer
import com.github.albertocavalcante.groovylsp.indexing.lsif.LsifWriter
import com.github.albertocavalcante.groovylsp.indexing.scip.ScipWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class IndexCommand : CliktCommand(name = "index") {

    override fun help(context: com.github.ajalt.clikt.core.Context) = "Generate SCIP or LSIF index for a project"

    private val format by option("--format", help = "Output format (SCIP or LSIF)")
        .enum<IndexFormat>()
        .default(IndexFormat.SCIP)

    private val output by option("--output", help = "Output file path")
        .file(mustExist = false)
        .default(File("index.scip"))

    private val projectRoot by argument(help = "Project root directory")
        .path(mustExist = true, canBeFile = false)
        .default(java.nio.file.Path.of("."))

    override fun run() {
        val rootPath = projectRoot.toAbsolutePath().normalize().toString()
        echo("Indexing $rootPath to $output in $format format...")

        val writers = when (format) {
            IndexFormat.SCIP -> listOf(ScipWriter(FileOutputStream(output), rootPath))
            IndexFormat.LSIF -> listOf(LsifWriter(FileOutputStream(output), rootPath))
        }

        try {
            val indexer = UnifiedIndexer(writers)
            // Walk files - use block ensures stream is closed to prevent file handle leaks
            Files.walk(projectRoot).use { stream ->
                stream.filter { it.toFile().extension in FileExtensions.ALL_GROOVY_LIKE }
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
            echo("Successfully generated index at ${output.absolutePath}")
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
