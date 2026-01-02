package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovycommon.FileExtensions
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.indexing.IndexFormat
import com.github.albertocavalcante.groovylsp.indexing.IndexWriter
import com.github.albertocavalcante.groovylsp.indexing.SymbolGenerator
import com.github.albertocavalcante.groovylsp.indexing.UnifiedIndexer
import com.github.albertocavalcante.groovylsp.indexing.lsif.LsifWriter
import com.github.albertocavalcante.groovylsp.indexing.scip.ScipWriter
import com.github.albertocavalcante.groovylsp.providers.indexing.ExportIndexParams
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

class IndexExportService(private val buildToolManagerProvider: () -> BuildToolManager?) {
    private val logger = LoggerFactory.getLogger(IndexExportService::class.java)

    fun exportIndex(params: ExportIndexParams, workspaceRoot: Path): String {
        logger.info("Exporting index format=${params.format} to=${params.outputPath}")

        FileOutputStream(params.outputPath).use { fileOutputStream ->
            val writers = createWriters(params.format, fileOutputStream, workspaceRoot)

            val buildToolManager = buildToolManagerProvider()
            val buildTool = buildToolManager?.detectBuildTool(workspaceRoot)
            val manager = buildTool?.name?.lowercase() ?: "manual"
            val symbolGenerator = SymbolGenerator(scheme = "scip-groovy", manager = manager)
            val indexer = UnifiedIndexer(writers, symbolGenerator)

            try {
                indexFiles(workspaceRoot, indexer)
            } finally {
                // Explicitly close writers to flush footers
                writers.forEach { it.close() }
            }
        }

        return "Successfully exported ${params.format} index to ${params.outputPath}"
    }

    private fun createWriters(format: IndexFormat, outputStream: FileOutputStream, root: Path): List<IndexWriter> =
        try {
            when (format) {
                IndexFormat.SCIP -> listOf(ScipWriter(outputStream, root.toString()))
                IndexFormat.LSIF -> listOf(LsifWriter(outputStream, root.toString()))
            }
        } catch (e: Exception) {
            try {
                outputStream.close()
            } catch (_: Exception) {
                // Ignore
            }
            throw e
        }

    private fun indexFiles(root: Path, indexer: UnifiedIndexer) {
        Files.walk(root).use { stream ->
            stream.filter { path ->
                path.extension in FileExtensions.EXTENSIONS ||
                    path.name in FileExtensions.FILENAMES
            }
                .forEach { path ->
                    try {
                        val relativePath = root.relativize(path).toString()
                        val content = path.readText()
                        indexer.indexDocument(relativePath, content)
                    } catch (e: Exception) {
                        logger.warn("Failed to index file $path", e)
                    }
                }
        }
    }
}
