package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.progress.ProgressReporter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.services.LanguageClient

private const val PERCENTAGE_MULTIPLIER = 100
private const val STATUS_UPDATE_INTERVAL_MS = 100L

/**
 * Handles workspace indexing operations for project startup.
 *
 * This class manages the background indexing of workspace source files,
 * providing progress updates and coordinating with strategy initialization.
 */
internal class WorkspaceIndexer(
    private val compilationService: GroovyCompilationService,
    private val coroutineScope: CoroutineScope,
    private val indexingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Starts workspace indexing in the background.
     *
     * @param client The LSP client for sending progress notifications.
     * @param onStatusUpdate Callback for status updates.
     * @param strategyInitJobs Jobs representing async strategy initialization.
     */
    fun startIndexing(client: LanguageClient?, onStatusUpdate: StatusUpdateCallback, strategyInitJobs: List<Job>) {
        val sourceUris = compilationService.workspaceManager.getWorkspaceSourceUris()
        if (sourceUris.isEmpty()) {
            logger.debug { "No workspace sources to index" }
            // No files to index, signal ready after making sure strategy init is done
            coroutineScope.launch(indexingDispatcher) {
                strategyInitJobs.forEach { it.join() }
                onStatusUpdate(Health.Ok, true, "Ready", null, null, null, null)
            }
            return
        }

        val total = sourceUris.size
        logger.info { "Starting workspace indexing: $total files" }

        // Send initial indexing status with file counts
        onStatusUpdate(Health.Ok, false, "Indexing $total files...", 0, total, null, null)

        val indexingProgressReporter = ProgressReporter(client)
        indexingProgressReporter.startDependencyResolution(
            title = "Indexing workspace",
            initialMessage = "Indexing $total Groovy files...",
        )

        coroutineScope.launch(indexingDispatcher) {
            runCatching {
                var lastStatusUpdate = System.currentTimeMillis()
                compilationService.indexAllWorkspaceSources(sourceUris) { indexed, totalFiles ->
                    val percentage = if (totalFiles > 0) (indexed * PERCENTAGE_MULTIPLIER / totalFiles) else 0
                    indexingProgressReporter.updateProgress("Indexed $indexed/$totalFiles files", percentage)
                    // Throttle status updates to avoid excessive notifications
                    val now = System.currentTimeMillis()
                    if (now - lastStatusUpdate >= STATUS_UPDATE_INTERVAL_MS || indexed == totalFiles) {
                        onStatusUpdate(
                            Health.Ok,
                            false,
                            "Indexing $indexed/$totalFiles files",
                            indexed,
                            totalFiles,
                            null,
                            null,
                        )
                        lastStatusUpdate = now
                    }
                }
                indexingProgressReporter.complete("✅ Indexed $total files")
                logger.info { "Workspace indexing complete: $total files" }

                // Trigger workspace compilation for cross-file semantic resolution
                logger.info { "Starting workspace compilation for cross-file resolution" }
                onStatusUpdate(Health.Ok, false, "Compiling workspace...", null, null, null, null)
                val workspaceCompiler = compilationService.getWorkspaceCompiler()
                val compilationResult = workspaceCompiler.compileWorkspace()
                logger.info {
                    "Workspace compilation complete: ${compilationResult.modules.size} modules, " +
                        "${compilationResult.errors.size} errors"
                }
                if (!compilationResult.success) {
                    logger.warn { "Workspace compilation had errors, but proceeding with partial results" }
                }

                // Ensure strategy initialization is also complete before signaling ready
                strategyInitJobs.forEach { it.join() }

                // Signal ready after indexing completes
                onStatusUpdate(Health.Ok, true, "Ready", total, total, null, null)
            }.onFailure { throwable ->
                rethrowIfCancellationOrError(throwable)
                logger.error(throwable) { "Workspace indexing failed" }
                indexingProgressReporter.completeWithError("Failed to index workspace: ${throwable.message}")
                // Signal warning state but still quiescent
                onStatusUpdate(Health.Warning, true, "Indexing failed: ${throwable.message}", null, null, null, null)
            }
        }
    }
}
