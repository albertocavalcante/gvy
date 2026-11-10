package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.Version
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SymbolIndexingService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.providers.symbols.toSymbolInformation
import com.github.albertocavalcante.gvy.gls.utils.isScannedUnder
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionResolver
import com.github.albertocavalcante.gvy.gls.worker.WorkerFeature
import com.github.albertocavalcante.gvy.gls.worker.WorkerRouter
import com.github.albertocavalcante.gvy.gls.worker.WorkerRouterFactory
import com.github.albertocavalcante.gvy.gls.worker.defaultWorkerDescriptors
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onClosed
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
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Handles all workspace related LSP operations.
 */
class GroovyWorkspaceService(
    private val compilationService: GroovyCompilationService,
    private val symbolIndexer: SymbolIndexingService,
    private val coroutineScope: CoroutineScope,
    private val textDocumentService: GroovyTextDocumentService? = null,
    private val workerRouter: WorkerRouter = WorkerRouter(defaultWorkerDescriptors()),
    sourceChangeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WorkspaceService {

    private val logger = KotlinLogging.logger {}

    /**
     * Source and folder events, applied one notification at a time in arrival order, off the LSP notification
     * thread: a folder event can walk a whole subtree (#1424). A later notification never overtakes an earlier one.
     * The consumer ends with [coroutineScope]; batches still queued at shutdown are dropped. If an [Error] ends it
     * early, the queue is closed with that cause, so every later notification logs what it dropped.
     */
    private val sourceChanges = Channel<List<FileEvent>>(Channel.UNLIMITED)

    init {
        val consumer = coroutineScope.launch(sourceChangeDispatcher) {
            for (batch in sourceChanges) {
                // One failed notification must not stop the queue for every later one.
                runCatching { handleSourceFileChanges(batch) }.onFailure { failure ->
                    rethrowIfCancellationOrError(failure)
                    logger.warn(failure) { "Failed to apply ${batch.size} watched source changes" }
                }
            }
        }
        consumer.invokeOnCompletion { cause ->
            sourceChanges.close(cause)
            if (cause != null && cause !is CancellationException) {
                logger.error(cause) { "Watched source queue stopped: source changes apply again after a restart" }
            }
        }
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        logger.info { "Executing command: ${params.command}" }
        return when (params.command) {
            "groovy.version" -> CompletableFuture.completedFuture(Version.current)
            else -> {
                logger.warn { "Unknown command: ${params.command}" }
                CompletableFuture.completedFuture(null)
            }
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        logger.info { "Configuration changed, updating Jenkins context if applicable" }

        @Suppress("UNCHECKED_CAST")
        val settingsMap = when (val settings = params.settings) {
            is Map<*, *> -> settings as? Map<String, Any>
            // LSP4J represents Object-typed notification payloads as JSON, unlike direct callers.
            is JsonObject -> Gson().fromJson(settings, Map::class.java) as Map<String, Any>
            else -> null
        }

        if (settingsMap != null) {
            val newConfig = ServerConfiguration.fromMap(settingsMap)
            textDocumentService?.updateConfiguration(newConfig)

            // Update configuration on all active strategies
            compilationService.workspaceManager.getStrategyRegistry()?.activeStrategies?.forEach { strategy ->
                strategy.updateConfiguration(newConfig)
            }
            compilationService.updateCompilationCacheLimit(newConfig.compilationCacheMaxEntries)
            updateGroovyVersion(newConfig)
            logger.info { "Configuration updated for active strategies" }
        }
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        logger.debug { "Watched files changed: ${params.changes?.size ?: 0} changes" }

        // A source root's folder watcher reports everything under it; what the scans skip changes nothing.
        val roots = compilationService.workspaceManager.getSourceRoots().map { it.toAbsolutePath().normalize() }
        val relevant = params.changes.orEmpty().filterNot { isSkipped(it.uri, roots) }
        if (relevant.isEmpty()) return
        textDocumentService?.invalidateWorkspaceFiles()

        val changes = relevant.groupBy { classifyFileChange(it.uri) }

        // Handle CodeNarc config changes
        changes[FileType.CODENARC]?.let { _ ->
            logger.info { "CodeNarc config changed, reloading rulesets" }
            textDocumentService?.reloadCodeNarcRulesets()
            // Re-run diagnostics on open files
            textDocumentService?.rerunDiagnosticsOnOpenFiles()
        }

        // Handle GDSL file changes
        val jenkinsCapabilities = compilationService.workspaceManager.getJenkinsCapabilities()
        val shouldReloadGdsl = params.changes.any { change ->
            try {
                val uri = URI.create(change.uri)
                jenkinsCapabilities?.isGdslFile(uri) ?: false
            } catch (e: Exception) {
                false
            }
        }

        if (shouldReloadGdsl) {
            logger.info { "GDSL file changed, reloading metadata" }
            jenkinsCapabilities?.reloadGdsl()
        }

        // Handle source file changes for incremental indexing, and folder events under the source roots (#1424).
        // Any other path may be a folder under a source root; only its creation or deletion matters.
        // A closed source's parse is retired now, an in-memory fence, so no reader gets the old parse while the
        // queued work waits: a Changed event, or an atomic save reported as Deleted plus Created. An open buffer
        // keeps its own parse; its Changed event has nothing left to do, its Created or Deleted still moves the list.
        // The parse may be cached under the listed spelling, not the event's (#1440): both are retired.
        val sourceEvents = changes[FileType.SOURCE].orEmpty()
        val listed = listedUris(sourceEvents)
        val queued = sourceEvents.filter { event ->
            val retired = uriOf(event.uri)?.let { uri -> retireClosed(uri, listed[uri]) } ?: false
            event.type != FileChangeType.Changed || retired
        } + changes[FileType.OTHER].orEmpty().filter { it.type != FileChangeType.Changed }
        if (queued.isNotEmpty()) enqueue(queued)

        // Build file changes are handled by BuildToolFileWatcher in DependencyManager
    }

    /**
     * Handles source and folder changes for incremental workspace indexing, one notification at a time.
     * - Created files and folders: join the source list and are indexed
     * - Changed files: Re-index to update symbols
     * - Deleted files and folders: leave the source list and the index
     *
     * Any path may be a folder (a folder named `lib.groovy` too): the source list decides from the disk.
     * A changed file was already retired when its notification arrived; it is retired again here, which also
     * skips it if its buffer was reopened while the batch waited.
     */
    private fun handleSourceFileChanges(changes: List<FileEvent>) {
        val sources = changes.filter { classifyFileChange(it.uri) == FileType.SOURCE }
        val changed = sources.filter { it.type == FileChangeType.Changed }
        // Spelled before the model update, while a deleted file is still listed (#1440).
        val listed = listedUris(sources)

        // One model update and at most one cache clear per notification, however many files it carries.
        val change = compilationService.onWorkspaceSourcesChanged(
            changes.pathsOf(FileChangeType.Created),
            changes.pathsOf(FileChangeType.Deleted),
        )
        if (change.changed) textDocumentService?.rerunDiagnosticsOnOpenFiles()

        // Deleted files, and the sources under deleted folders, leave the caches and the index.
        val removed = sources.filter { it.type == FileChangeType.Deleted }.mapNotNull { uriOf(it.uri) }
            .flatMap { uri -> listOfNotNull(uri, listed[uri]) } + change.removed.map { it.toUri() }
        removed.distinct().forEach { uri ->
            compilationService.invalidateCache(uri)
            logger.debug { "Removed deleted file from index: $uri" }
        }

        // Index new and changed files, and the sources found in created folders, in background, each under its
        // listed spelling only: indexing the event's spelling too would list its symbols twice (#1440).
        // Distinct: a source root's folder watcher can repeat an event the file watchers also report.
        val candidates = (sources.filter { it.type == FileChangeType.Created } + changed).mapNotNull { uriOf(it.uri) }
            .map { uri -> uri to (listed[uri] ?: uri) }
            .plus(change.added.map { it.toUri() }.map { uri -> uri to uri })
            .distinctBy { (_, listedUri) -> listedUri }

        // A closed file's cached parse no longer describes it; an open buffer keeps its own. Applied after the
        // notification, so a buffer may have been reopened meanwhile: that file is skipped, as didClose skips it.
        val toIndex = candidates.filter { (uri, listedUri) -> retireClosed(uri, listedUri) }.map { it.second }

        if (toIndex.isNotEmpty()) {
            logger.info { "Indexing ${toIndex.size} changed source files" }
            coroutineScope.launch {
                symbolIndexer.indexAllWorkspaceSources(toIndex)
                compilationService.refreshSemanticFromDisk(toIndex)
            }
        }
    }

    private fun filePath(uri: String): Path? = runCatching { Path.of(URI.create(uri)) }.getOrNull()

    /** The listed spelling of each source event's URI that names one (#1440); a URI already listed maps to itself. */
    private fun listedUris(events: List<FileEvent>): Map<URI, URI> {
        val paths = events.mapNotNull { event ->
            val uri = uriOf(event.uri) ?: return@mapNotNull null
            filePath(event.uri)?.let { uri to it }
        }
        val spellings = compilationService.workspaceManager.listedSpellings(paths.map { it.second })
        return paths.associate { (uri, path) -> uri to (spellings[path]?.toUri() ?: uri) }
    }

    /**
     * Retires a closed source's parse under the event's spelling [uri] and its [listed] one (#1440). Nothing is
     * retired, and false returned, while a buffer is open under either spelling: its parse is the buffer's.
     */
    private fun retireClosed(uri: URI, listed: URI?): Boolean {
        val spellings = listOfNotNull(uri, listed).distinct()
        if (spellings.any(compilationService::isDocumentOpen)) return false
        // Each spelling is retired separately, not atomically: a compile between them holds a fresh ticket and
        // commits current facts. Each retirement re-checks under the fence, so a buffer opened meanwhile is kept.
        val tickets = spellings.map { compilationService.onClosedSourceChanged(it, keepIfUnchanged = false) }
        return tickets.all { it != null }
    }

    private fun uriOf(uri: String): URI? = runCatching { URI.create(uri) }.getOrNull()

    private fun enqueue(batch: List<FileEvent>) {
        sourceChanges.trySend(batch).onClosed { cause ->
            if (cause == null || cause is CancellationException) {
                logger.debug { "Dropped ${batch.size} watched source changes after shutdown" }
            } else {
                logger.error(cause) { "Dropped ${batch.size} watched source changes: the source queue stopped" }
            }
        }
    }

    /** Path checks only, before any disk read: under a source root, inside a directory the source scan skips. */
    private fun isSkipped(uri: String, roots: List<Path>): Boolean {
        val path = filePath(uri)?.toAbsolutePath()?.normalize() ?: return false
        return roots.any { path.startsWith(it) } && !isScannedUnder(path, roots)
    }

    private fun List<FileEvent>.pathsOf(type: FileChangeType): List<Path> =
        filter { it.type == type }.mapNotNull { filePath(it.uri) }

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
                logger.debug { "No workspace sources to reindex after worker change" }
                return
            }
            logger.info { "Worker changed; reindexing ${sourceUris.size} workspace sources" }
            coroutineScope.launch {
                symbolIndexer.indexAllWorkspaceSources(sourceUris)
                // The worker change cleared the semantic index; rebuild it unless the workspace is too large.
                if (!config.isWorkspaceTooLarge(sourceUris.size)) {
                    compilationService.getWorkspaceCompiler().compileWorkspace()
                }
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
            logger.debug { "Workspace symbol query blank; returning empty result" }
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }
        val storages = symbolIndexer.getAllSymbolIndices()
        val results = storages.flatMap { (uri, storage) ->
            val symbols: List<Symbol> = storage.findMatching(uri, query)

            symbols.mapNotNull { it.toSymbolInformation() }
        }

        return CompletableFuture.completedFuture(Either.forLeft(results))
    }
}
