package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovylsp.indexing.IndexFormat
import com.github.albertocavalcante.groovylsp.indexing.IndexWriter
import com.github.albertocavalcante.groovylsp.indexing.SymbolGenerator
import com.github.albertocavalcante.groovylsp.indexing.UnifiedIndexer
import com.github.albertocavalcante.groovylsp.indexing.lsif.LsifWriter
import com.github.albertocavalcante.groovylsp.indexing.scip.ScipWriter
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.common.FileExtensions
import com.github.albertocavalcante.gvy.gls.providers.indexing.ExportIndexParams
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

class IndexExportService(private val buildToolManagerProvider: () -> BuildToolManager?) {
    private val logger = KotlinLogging.logger {}

    fun exportIndex(params: ExportIndexParams, workspaceRoot: Path): String {
        logger.info { "Exporting index format=${params.format} to=${params.outputPath}" }

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
        runCatching {
            when (format) {
                IndexFormat.SCIP -> listOf(ScipWriter(outputStream, root.toString()))
                IndexFormat.LSIF -> listOf(LsifWriter(outputStream, root.toString()))
            }
        }
            .onFailure { throwable ->
                rethrowIfCancellationOrError(throwable)
                runCatching { outputStream.close() }
                throw throwable
            }
            .getOrThrow()

    private fun indexFiles(root: Path, indexer: UnifiedIndexer) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root && isExcludedDirectory(dir.name)) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile &&
                        (file.extension in FileExtensions.EXTENSIONS || file.name in FileExtensions.FILENAMES)
                    ) {
                        indexFile(root, file, indexer)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    logger.warn(exc) { "Skipping unreadable path $file" }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun indexFile(root: Path, path: Path, indexer: UnifiedIndexer) {
        runCatching {
            val relativePath = root.relativize(path).toString()
            val content = path.readText()
            indexer.indexDocument(relativePath, content)
        }
            .onFailure { throwable ->
                rethrowIfCancellationOrError(throwable)
                // Keep indexing other files; log for debugging.
                logger.warn(throwable) { "Failed to index file $path" }
            }
    }

    private companion object {
        /** Build/output directories that never contain hand-written sources (hidden dirs are handled separately). */
        val BUILD_OUTPUT_DIRECTORIES = setOf("build", "out", "target", "node_modules")

        /**
         * Hidden directories (.git, .gradle, .idea, .jenkins-stubs, ...) and build outputs hold tooling state or
         * generated code, so exporting them wastes time and adds bogus symbols to the index.
         */
        fun isExcludedDirectory(name: String): Boolean = name.startsWith(".") || name in BUILD_OUTPUT_DIRECTORIES
    }
}
