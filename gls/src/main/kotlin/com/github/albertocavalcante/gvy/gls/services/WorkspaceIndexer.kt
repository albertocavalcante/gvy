package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.progress.ProgressReporter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.atomic.AtomicBoolean

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
    private val tooLargeNotified = AtomicBoolean(false)

    /**
     * Starts workspace indexing in the background.
     *
     * @param client The LSP client for sending progress notifications.
     * @param onStatusUpdate Callback for status updates.
     * @param strategyInitJobs Jobs representing async strategy initialization.
     * @param config Server configuration; its `maxWorkspaceFiles` limit gates the eager workspace compile.
     */
    fun startIndexing(
        client: LanguageClient?,
        onStatusUpdate: StatusUpdateCallback,
        strategyInitJobs: List<Job>,
        config: ServerConfiguration,
    ) {
        val sourceUris = compilationService.workspaceManager.getWorkspaceSourceUris()
        if (sourceUris.isEmpty()) {
            handleEmptyWorkspace(strategyInitJobs, onStatusUpdate)
            return
        }

        val total = sourceUris.size
        logger.info { "Starting workspace indexing: $total files" }
        onStatusUpdate(Health.Ok, false, "Indexing $total files...", 0, total, null, null)

        val indexingProgressReporter = ProgressReporter(client)
        indexingProgressReporter.startDependencyResolution(
            title = "Indexing workspace",
            initialMessage = "Indexing $total Groovy files...",
        )

        coroutineScope.launch(indexingDispatcher) {
            runCatching {
                performIndexing(sourceUris, total, indexingProgressReporter, onStatusUpdate)
                performCompilation(total, config, client, onStatusUpdate)
                strategyInitJobs.forEach { it.join() }
                onStatusUpdate(Health.Ok, true, "Ready", total, total, null, null)
            }.onFailure { throwable ->
                handleIndexingFailure(throwable, indexingProgressReporter, onStatusUpdate)
            }
        }
    }

    private fun handleEmptyWorkspace(strategyInitJobs: List<Job>, onStatusUpdate: StatusUpdateCallback) {
        logger.debug { "No workspace sources to index" }
        coroutineScope.launch(indexingDispatcher) {
            strategyInitJobs.forEach { it.join() }
            onStatusUpdate(Health.Ok, true, "Ready", null, null, null, null)
        }
    }

    private suspend fun performIndexing(
        sourceUris: List<java.net.URI>,
        total: Int,
        progressReporter: ProgressReporter,
        onStatusUpdate: StatusUpdateCallback,
    ) {
        var lastStatusUpdate = System.currentTimeMillis()
        compilationService.indexAllWorkspaceSources(sourceUris) { indexed, totalFiles ->
            val percentage = if (totalFiles > 0) (indexed * PERCENTAGE_MULTIPLIER / totalFiles) else 0
            progressReporter.updateProgress("Indexed $indexed/$totalFiles files", percentage)
            val now = System.currentTimeMillis()
            if (now - lastStatusUpdate >= STATUS_UPDATE_INTERVAL_MS || indexed == totalFiles) {
                onStatusUpdate(Health.Ok, false, "Indexing $indexed/$totalFiles files", indexed, totalFiles, null, null)
                lastStatusUpdate = now
            }
        }
        progressReporter.complete("✅ Indexed $total files")
        logger.info { "Workspace indexing complete: $total files" }
    }

    private suspend fun performCompilation(
        total: Int,
        config: ServerConfiguration,
        client: LanguageClient?,
        onStatusUpdate: StatusUpdateCallback,
    ) {
        if (config.isWorkspaceTooLarge(total)) {
            skipCompilationForLargeWorkspace(total, config.maxWorkspaceFiles, client)
            return
        }
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
    }

    private fun skipCompilationForLargeWorkspace(total: Int, limit: Int, client: LanguageClient?) {
        logger.warn {
            "Workspace has $total files, over the limit of $limit " +
                "(groovy.compilation.maxWorkspaceFiles); skipping eager workspace compilation. " +
                "Files are still compiled when opened."
        }
        if (client != null && tooLargeNotified.compareAndSet(false, true)) {
            client.showMessage(
                MessageParams().apply {
                    type = MessageType.Info
                    message = "Workspace has $total files, over the limit of $limit, so the whole workspace " +
                        "is not compiled up front; files are compiled when opened. " +
                        "To raise the limit, change groovy.compilation.maxWorkspaceFiles and restart the " +
                        "server (or reload the window); the limit is read once at startup."
                },
            )
        }
    }

    private fun handleIndexingFailure(
        throwable: Throwable,
        progressReporter: ProgressReporter,
        onStatusUpdate: StatusUpdateCallback,
    ) {
        rethrowIfCancellationOrError(throwable)
        logger.error(throwable) { "Workspace indexing failed" }
        progressReporter.completeWithError("Failed to index workspace: ${throwable.message}")
        onStatusUpdate(Health.Warning, true, "Indexing failed: ${throwable.message}", null, null, null, null)
    }
}
