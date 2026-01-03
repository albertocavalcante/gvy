package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.Version
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.providers.symbols.toSymbolInformation
import com.github.albertocavalcante.groovylsp.version.GroovyVersionInfo
import com.github.albertocavalcante.groovylsp.version.GroovyVersionResolver
import com.github.albertocavalcante.groovylsp.worker.WorkerFeature
import com.github.albertocavalcante.groovylsp.worker.WorkerRouter
import com.github.albertocavalcante.groovylsp.worker.WorkerRouterFactory
import com.github.albertocavalcante.groovylsp.worker.defaultWorkerDescriptors
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Handles all workspace related LSP operations.
 */
class GroovyWorkspaceService(
    private val compilationService: GroovyCompilationService,
    private val coroutineScope: CoroutineScope,
    private val textDocumentService: GroovyTextDocumentService? = null,
    private val workerRouter: WorkerRouter = WorkerRouter(defaultWorkerDescriptors()),
) : WorkspaceService {

    private val logger = LoggerFactory.getLogger(GroovyWorkspaceService::class.java)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        logger.info("Executing command: ${params.command}")
        return when (params.command) {
            "groovy.version" -> CompletableFuture.completedFuture(Version.current)
            else -> {
                logger.warn("Unknown command: ${params.command}")
                CompletableFuture.completedFuture(null)
            }
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        logger.info("Configuration changed, updating Jenkins context if applicable")

        // Parse new configuration
        val settings = params.settings

        @Suppress("UNCHECKED_CAST")
        val settingsMap = when (val settings = params.settings) {
            is Map<*, *> -> settings as? Map<String, Any>
            else -> null
        }

        if (settingsMap != null) {
            val newConfig = ServerConfiguration.fromMap(settingsMap)

            // Update Jenkins workspace configuration
            compilationService.workspaceManager.updateJenkinsConfiguration(newConfig)
            updateGroovyVersion(newConfig)
            logger.info("Jenkins configuration updated")
        }
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        logger.debug("Watched files changed: ${params.changes?.size ?: 0} changes")

        if (params.changes.isNullOrEmpty()) return

        val changes = params.changes.groupBy { classifyFileChange(it.uri) }

        // Handle CodeNarc config changes
        changes[FileType.CODENARC]?.let { _ ->
            logger.info("CodeNarc config changed, reloading rulesets")
            textDocumentService?.reloadCodeNarcRulesets()
            // Re-run diagnostics on open files
            textDocumentService?.rerunDiagnosticsOnOpenFiles()
        }

        // Handle GDSL file changes
        val shouldReloadGdsl = params.changes.any { change ->
            try {
                val uri = URI.create(change.uri)
                compilationService.workspaceManager.isGdslFile(uri)
            } catch (e: Exception) {
                false
            }
        }

        if (shouldReloadGdsl) {
            logger.info("GDSL file changed, reloading metadata")
            compilationService.workspaceManager.reloadJenkinsGdsl()
        }

        // Handle source file changes for incremental indexing
        changes[FileType.SOURCE]?.let { sourceChanges ->
            handleSourceFileChanges(sourceChanges)
        }

        // Build file changes are handled by BuildToolFileWatcher in DependencyManager
    }

    /**
     * Handles source file changes for incremental workspace indexing.
     * - Created files: Index the new file
     * - Changed files: Re-index to update symbols
     * - Deleted files: Remove from symbol index
     */
    private fun handleSourceFileChanges(changes: List<FileEvent>) {
        val created = changes.filter { it.type == FileChangeType.Created }
        val changed = changes.filter { it.type == FileChangeType.Changed }
        val deleted = changes.filter { it.type == FileChangeType.Deleted }

        // Handle deleted files - remove from cache
        deleted.forEach { event ->
            try {
                val uri = URI.create(event.uri)
                compilationService.invalidateCache(uri)
                logger.debug("Removed deleted file from index: ${event.uri}")
            } catch (e: Exception) {
                logger.debug("Failed to process deleted file: ${event.uri}", e)
            }
        }

        // Index new and changed files in background
        val toIndex = (created + changed).mapNotNull { event ->
            try {
                URI.create(event.uri)
            } catch (e: Exception) {
                null
            }
        }

        if (toIndex.isNotEmpty()) {
            logger.info("Indexing ${toIndex.size} changed source files")
            coroutineScope.launch {
                compilationService.indexAllWorkspaceSources(toIndex)
            }
        }
    }

    private enum class FileType(val suffixes: List<String>) {
        CODENARC(listOf(".codenarc", "codenarc.xml", "codenarc.groovy", "codenarc.properties")),
        GDSL(listOf(".gdsl")),
        BUILD(
            listOf(
                "build.gradle",
                "build.gradle.kts",
                "settings.gradle",
                "settings.gradle.kts",
                "pom.xml",
                "gradle.properties",
            ),
        ),
        SOURCE(listOf(".groovy", ".java")),
        OTHER(emptyList()),
        ;

        companion object {
            fun fromPath(path: String): FileType =
                entries.firstOrNull { type -> type.suffixes.any { path.endsWith(it) } } ?: OTHER
        }
    }

    private fun classifyFileChange(uriString: String): FileType {
        val path = runCatching { URI.create(uriString).path }.getOrNull() ?: return FileType.OTHER
        return FileType.fromPath(path)
    }

    private fun updateGroovyVersion(config: ServerConfiguration) {
        val resolver = GroovyVersionResolver()
        val dependencies = compilationService.workspaceManager.getDependencyClasspath()
        val info = resolver.resolve(dependencies, config.groovyLanguageVersion)
        compilationService.updateGroovyVersion(info)
        selectWorker(info, config)
    }

    private fun selectWorker(
        info: GroovyVersionInfo,
        config: ServerConfiguration,
        requiredFeatures: Set<WorkerFeature> = emptySet(),
    ) {
        val router = resolveWorkerRouter(config)
        val selected = router.select(info, requiredFeatures)
        val changed = compilationService.updateSelectedWorker(selected)
        if (changed) {
            val sourceUris = compilationService.workspaceManager.getWorkspaceSourceUris()
            if (sourceUris.isEmpty()) {
                logger.debug("No workspace sources to reindex after worker change")
                return
            }
            logger.info("Worker changed; reindexing ${sourceUris.size} workspace sources")
            coroutineScope.launch {
                compilationService.indexAllWorkspaceSources(sourceUris)
            }
        }
    }

    private fun resolveWorkerRouter(config: ServerConfiguration): WorkerRouter {
        if (config.workerDescriptors.isEmpty()) {
            return workerRouter
        }
        return WorkerRouterFactory.fromConfig(config)
    }

    override fun symbol(
        params: WorkspaceSymbolParams,
    ): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        val query = params.query
        if (query.isNullOrBlank()) {
            logger.debug("Workspace symbol query blank; returning empty result")
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }
        val storages = compilationService.getAllSymbolStorages()
        val results = storages.flatMap { (uri, storage) ->
            val symbols: List<Symbol> = storage.findMatching(uri, query)

            symbols.mapNotNull { it.toSymbolInformation() }
        }

        return CompletableFuture.completedFuture(Either.forLeft(results))
    }
}
