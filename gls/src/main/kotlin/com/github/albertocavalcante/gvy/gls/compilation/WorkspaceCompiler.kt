// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserSeverity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Error encountered during workspace compilation.
 */
data class CompilationError(val uri: URI, val message: String, val phase: String)

/**
 * Result of workspace compilation containing all compiled modules.
 */
data class WorkspaceCompilationResult(
    val modules: Map<URI, ModuleNode>,
    val errors: List<CompilationError>,
    val success: Boolean,
)

/**
 * Compiles the entire workspace together (instead of per-file) for full cross-file semantic resolution.
 *
 * This compiler addresses the limitation of single-file compilation by compiling all workspace files
 * together using the Groovy compiler's multi-source compilation capabilities. This enables:
 * - Full cross-file type resolution
 * - Cross-file method and field resolution
 * - Proper inheritance chain resolution across files
 *
 * The compiler uses the SEMANTIC_ANALYSIS phase (instead of just CANONICALIZATION) to achieve
 * full type resolution, accepting slower startup time for accuracy.
 *
 * Files compile in parallel on Dispatchers.Default, and every successful compile feeds the shared
 * [SemanticIndexer], so cross-file references cover files that were never opened.
 *
 * @param workerSessionManager The worker session manager for executing compilation
 * @param workspaceManager The workspace manager providing workspace sources and configuration
 * @param semanticIndexer The shared owner of the workspace semantic index
 * @param ownership Publication fence shared with the other artifacts of the same files
 */
class WorkspaceCompiler(
    private val workerSessionManager: WorkerSessionManager,
    private val workspaceManager: WorkspaceManager,
    private val semanticIndexer: SemanticIndexer,
    val dependencyGraph: DependencyGraph = DependencyGraph(),
    private val ownership: ArtifactOwnership = ArtifactOwnership(),
) {
    private val logger = KotlinLogging.logger {}

    // Remembers the eager compile attempt so later refreshes can materialize missing source documents.
    @Volatile
    private var workspaceCompiled = false

    /**
     * Compiles all workspace files together for full cross-file semantic resolution.
     *
     * This method:
     * 1. Collects all Groovy source files from the workspace
     * 2. Compiles them together using SEMANTIC_ANALYSIS phase
     * 3. Extracts the ModuleNode for each file
     * 4. Caches the results for later retrieval
     *
     * @return WorkspaceCompilationResult containing all compiled modules and any errors
     */
    suspend fun compileWorkspace(): WorkspaceCompilationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        logger.info { "Starting workspace compilation" }

        // One workspace model for the whole batch: sources, classpath and roots agree (H1)
        val workspace = workspaceManager.current()
        val workspaceSources = workspace.sources
        logger.info { "Found ${workspaceSources.size} workspace sources to compile" }

        if (workspaceSources.isEmpty()) {
            logger.info { "No workspace sources found, returning empty result" }
            return@withContext WorkspaceCompilationResult(
                modules = emptyMap(),
                errors = emptyList(),
                success = true,
            )
        }

        // Compile all files together
        val result = compileFiles(workspaceSources, workspace)
        workspaceCompiled = true
        if (!result.success) {
            logger.warn { "Workspace compilation produced partial semantic results (${result.errors.size} errors)" }
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.info {
            "Workspace compilation completed in ${elapsed}ms: " +
                "${result.modules.size} modules, ${result.errors.size} errors"
        }

        result
    }

    /**
     * Re-derives the semantic documents of [uris] from the files on disk (after a watcher event, or once
     * the editor buffer a file followed is closed).
     *
     * A file that is gone, unreadable, not a `.groovy` source, or never existed (`untitled:`) loses its
     * document. An open file keeps following its buffer, and one whose document already matches the disk
     * is skipped. Each compile includes every workspace source, so many changed files (a branch switch)
     * get one workspace compile instead of one each. Without the eager workspace compile (the workspace is
     * over the configured size limit) only files that already have a document are refreshed.
     */
    suspend fun refreshFromDisk(uris: Collection<URI>) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val admission = SourceInputAdmission(workspaceManager::isExcludedSource) { context.ensureActive() }
        val stale = uris.filter { needsRefresh(it, admission) }
        when {
            stale.isEmpty() -> Unit
            workspaceCompiled && stale.size > BATCH_REFRESH_LIMIT -> compileWorkspace()
            else -> {
                val workspace = workspaceManager.current()
                stale.filter { workspaceCompiled || it in semanticIndexer }.forEach { uri ->
                    val result = compileRefreshWithAdmissionRetry(
                        checkNotNull(UriPathConverter.toPath(uri)),
                        workspace.sources,
                        workspace.classpath,
                        workspace.sourceRoots,
                    )
                    when (result) {
                        is FileCompilationResult.Success -> Unit
                        is FileCompilationResult.Failure -> logger.warn {
                            "Could not refresh semantic source for $uri: ${result.errors.joinToString { it.message }}"
                        }
                        is FileCompilationResult.Skipped -> Unit // It is currently excluded or unavailable.
                    }
                }
            }
        }
    }

    /** Whether [uri]'s document must be rebuilt from disk; drops it when there is no source file to build from. */
    private fun needsRefresh(uri: URI, admission: SourceInputAdmission): Boolean {
        // Owned before the open check: a reopen in between makes this removal stale (#1184).
        val ticket = ownership.begin(uri)
        if (semanticIndexer.followsBuffer(uri)) return false
        val path = UriPathConverter.toPath(uri)?.takeIf { it.extension.equals("groovy", ignoreCase = true) }
        if (path == null || !admission.allows(path)) {
            removeClosed(uri, ticket)
            return false
        }
        // Read once: the file may vanish between a check and the read (a checkout in progress).
        val content =
            try {
                path.readText()
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        admission.checkCancelled()
        return if (content == null || !admission.sourcePathAdmission()(path)) {
            removeClosed(uri, ticket)
            false
        } else {
            !semanticIndexer.isCurrent(uri, content)
        }
    }

    private fun removeClosed(uri: URI, ticket: ArtifactOwnership.Ticket) {
        ownership.commit(ticket) { if (!semanticIndexer.followsBuffer(uri)) semanticIndexer.remove(uri) }
    }

    /**
     * Compiles a list of workspace files together.
     *
     * This uses the Groovy compiler's ability to compile multiple sources in a single
     * compilation unit, which enables cross-file resolution.
     */
    private suspend fun compileFiles(sources: List<Path>, workspace: WorkspaceModel): WorkspaceCompilationResult {
        val modules = mutableMapOf<URI, ModuleNode>()
        val errors = mutableListOf<CompilationError>()
        val context = currentCoroutineContext()

        // Get workspace configuration
        val classpath = workspace.classpath
        val sourceRoots = workspace.sourceRoots

        // We need to compile each file separately but with all workspace sources available
        // to the compilation unit. This allows the compiler to resolve cross-file references.
        // We do this in parallel for better performance.
        // Use Dispatchers.Default for CPU-bound compilation work
        val results = coroutineScope {
            val compilationJobs = sources.map { sourcePath ->
                async(Dispatchers.Default) {
                    compileFileWithAdmissionRetry(sourcePath, sources, classpath, sourceRoots)
                }
            }

            // Await all compilations
            compilationJobs.awaitAll()
        }

        // Admission can recover after a per-file retry returns. Recheck remaining skips at the batch boundary
        // and make one final bounded attempt before reporting a still-admitted source as incomplete.
        val skippedPaths = results.filterIsInstance<FileCompilationResult.Skipped>()
            .map(FileCompilationResult.Skipped::sourcePath)
            .distinct()
        val retryAdmission = SourceInputAdmission(workspaceManager::isExcludedSource) { context.ensureActive() }
        val retryPaths = skippedPaths.filter(retryAdmission::allows)
        val retried = if (retryPaths.isEmpty()) {
            emptyList()
        } else {
            coroutineScope {
                retryPaths.map { sourcePath ->
                    async(Dispatchers.Default) {
                        compileFileWithAdmissionRetry(sourcePath, sources, classpath, sourceRoots)
                    }
                }.awaitAll()
            }
        }

        // Process both initial and retry results.
        results.forEach { result ->
            collect(result, modules, errors)
        }
        retried.forEach { result ->
            collect(result, modules, errors)
        }

        val retryPathSet = retryPaths.toSet()
        val retrySkippedPaths = retried.filterIsInstance<FileCompilationResult.Skipped>()
            .map(FileCompilationResult.Skipped::sourcePath)
        val unresolvedSkips = (skippedPaths.filterNot { it in retryPathSet } + retrySkippedPaths).distinct()
        val completionAdmission = SourceInputAdmission(workspaceManager::isExcludedSource) { context.ensureActive() }
        unresolvedSkips.filter(completionAdmission::allows).forEach { sourcePath ->
            errors += CompilationError(
                sourcePath.toUri(),
                "Source admission changed repeatedly while compiling",
                "admission",
            )
        }

        val success = errors.isEmpty()
        return WorkspaceCompilationResult(modules, errors, success)
    }

    private fun collect(
        result: FileCompilationResult,
        modules: MutableMap<URI, ModuleNode>,
        errors: MutableList<CompilationError>,
    ) {
        when (result) {
            is FileCompilationResult.Success -> {
                modules[result.uri] = result.module
                errors.addAll(result.errors)
            }
            is FileCompilationResult.Failure -> errors.addAll(result.errors)
            is FileCompilationResult.Skipped -> Unit
        }
    }

    /**
     * Compiles a single file with the full workspace context.
     *
     * The file is compiled with all workspace sources available to the compilation unit,
     * enabling cross-file resolution.
     */
    @Suppress("ReturnCount") // Admission failures exit inside the try so publication cleanup always runs.
    private suspend fun compileFileWithWorkspaceContext(
        sourcePath: Path,
        allSources: List<Path>,
        classpath: List<Path>,
        sourceRoots: List<Path>,
    ): FileCompilationResult {
        val uri = sourcePath.toUri()
        val context = currentCoroutineContext()
        val admission = SourceInputAdmission(workspaceManager::isExcludedSource) { context.ensureActive() }
        val sourcePathAdmission = admission.sourcePathAdmission()
        val (ticket, publication) = ownership.acquire(uri) { semanticIndexer.begin(uri) }

        fun freshlyAllowed(path: Path): Boolean {
            context.ensureActive()
            return sourcePathAdmission(path)
        }

        return try {
            if (!admission.allows(sourcePath)) {
                removeClosed(uri, ticket)
                return FileCompilationResult.Skipped(sourcePath)
            }
            val content = readSourceContent(sourcePath, uri, ticket)
            if (!freshlyAllowed(sourcePath)) {
                removeClosed(uri, ticket)
                return FileCompilationResult.Skipped(sourcePath)
            }
            val admittedRoots = sourceRoots.filter(admission::allows)
            val admittedSources = allSources.filter(admission::allows)

            // Build locator candidates for this file
            val locatorCandidates = buildLocatorCandidates(uri, sourcePath)

            // Create parse request with SEMANTIC_ANALYSIS phase for full type resolution
            val parseRequest = ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = admittedRoots,
                workspaceSources = admittedSources,
                locatorCandidates = locatorCandidates,
                compilePhase = Phases.SEMANTIC_ANALYSIS,
                checkCancelled = admission.checkCancelled,
                sourcePathAdmission = sourcePathAdmission,
            )

            // Parse using worker session
            val parseResult = workerSessionManager.parse(parseRequest)

            if (
                !freshlyAllowed(sourcePath) ||
                sourceRoots.filter(::freshlyAllowed) != admittedRoots ||
                allSources.filter(::freshlyAllowed) != admittedSources
            ) {
                removeClosed(uri, ticket)
                try {
                    parseResult.compilationUnit.classLoader.close()
                } catch (failure: IOException) {
                    logger.warn(failure) { "Failed to close a discarded workspace compilation for $uri" }
                }
                return FileCompilationResult.Skipped(sourcePath)
            }

            publishCompilationResult(uri, content, parseResult, publication)
        } catch (e: Exception) {
            rethrowIfCancellationOrError(e) // cancellation is not a compile error of this file
            logger.error(e) { "Exception compiling $uri" }
            semanticAnalysisFailure(uri, e.message ?: "Unknown error: ${e.javaClass.simpleName}")
        } finally {
            admission.releaseObservedPaths()
            publication.close()
        }
    }

    /** Retry one admission skip if the source is eligible again; report repeated churn instead of hiding it. */
    private suspend fun compileFileWithAdmissionRetry(
        sourcePath: Path,
        allSources: List<Path>,
        classpath: List<Path>,
        sourceRoots: List<Path>,
    ): FileCompilationResult {
        val first = compileFileWithWorkspaceContext(sourcePath, allSources, classpath, sourceRoots)
        if (first !is FileCompilationResult.Skipped) return first

        val context = currentCoroutineContext()
        val retryAdmission = SourceInputAdmission(workspaceManager::isExcludedSource) { context.ensureActive() }
        if (!retryAdmission.allows(sourcePath)) return first

        val retry = compileFileWithWorkspaceContext(sourcePath, allSources, classpath, sourceRoots)
        if (retry !is FileCompilationResult.Skipped) return retry

        val completionAdmission = SourceInputAdmission(workspaceManager::isExcludedSource) { context.ensureActive() }
        return if (completionAdmission.allows(sourcePath)) {
            admissionChangedFailure(sourcePath)
        } else {
            retry
        }
    }

    /** A refresh has no batch boundary, so recheck once after a skip before leaving the document absent. */
    private suspend fun compileRefreshWithAdmissionRetry(
        sourcePath: Path,
        allSources: List<Path>,
        classpath: List<Path>,
        sourceRoots: List<Path>,
    ): FileCompilationResult {
        val first = compileFileWithAdmissionRetry(sourcePath, allSources, classpath, sourceRoots)
        if (first !is FileCompilationResult.Skipped) return first

        val context = currentCoroutineContext()
        val retryAdmission = SourceInputAdmission(workspaceManager::isExcludedSource) { context.ensureActive() }
        if (!retryAdmission.allows(sourcePath)) return first

        val retry = compileFileWithAdmissionRetry(sourcePath, allSources, classpath, sourceRoots)
        if (retry !is FileCompilationResult.Skipped) return retry

        val completionAdmission = SourceInputAdmission(workspaceManager::isExcludedSource) { context.ensureActive() }
        return if (completionAdmission.allows(sourcePath)) admissionChangedFailure(sourcePath) else retry
    }

    private fun admissionChangedFailure(sourcePath: Path) = FileCompilationResult.Failure(
        listOf(
            CompilationError(
                sourcePath.toUri(),
                "Source admission changed repeatedly while compiling",
                "admission",
            ),
        ),
    )

    private fun readSourceContent(sourcePath: Path, uri: URI, ticket: ArtifactOwnership.Ticket): String = try {
        sourcePath.readText()
    } catch (failure: IOException) {
        removeClosed(uri, ticket)
        throw failure
    } catch (failure: SecurityException) {
        removeClosed(uri, ticket)
        throw failure
    }

    private fun publishCompilationResult(
        uri: URI,
        content: String,
        parseResult: ParseResult,
        publication: SemanticIndexer.Publication,
    ): FileCompilationResult {
        // Extract module
        val module = parseResult.ast
        return if (module != null) {
            logger.debug { "Successfully compiled: $uri" }
            semanticIndexer.publish(
                publication,
                content,
                module,
                parseResult.outcome.achievedPhase,
                fromBuffer = false,
            )
            val errors = parseResult.diagnostics.filter { it.severity == ParserSeverity.ERROR }.map {
                CompilationError(uri, it.message, "SEMANTIC_ANALYSIS")
            }
            FileCompilationResult.Success(uri, module, errors)
        } else {
            val errorMessage = if (parseResult.diagnostics.isNotEmpty()) {
                parseResult.diagnostics.joinToString("; ") { it.message }
            } else {
                "No AST produced"
            }
            logger.warn { "Failed to compile $uri: $errorMessage" }
            semanticAnalysisFailure(uri, errorMessage)
        }
    }

    private fun semanticAnalysisFailure(uri: URI, message: String) = FileCompilationResult.Failure(
        listOf(CompilationError(uri = uri, message = message, phase = "SEMANTIC_ANALYSIS")),
    )

    /**
     * Builds locator candidates for a source file.
     * These help the compiler locate the source unit during error reporting.
     */
    private fun buildLocatorCandidates(uri: URI, sourcePath: Path): Set<String> {
        val candidates = mutableSetOf<String>()
        candidates += uri.toString()
        candidates += uri.path
        candidates += sourcePath.toString()
        candidates += sourcePath.toAbsolutePath().toString()
        return candidates
    }

    /**
     * Internal result type for individual file compilation.
     */
    private sealed class FileCompilationResult {
        data class Success(val uri: URI, val module: ModuleNode, val errors: List<CompilationError>) :
            FileCompilationResult()
        data class Failure(val errors: List<CompilationError>) : FileCompilationResult()

        data class Skipped(val sourcePath: Path) : FileCompilationResult()
    }

    private companion object {
        /** Changed files beyond which one workspace compile is cheaper than compiling each against it. */
        const val BATCH_REFRESH_LIMIT = 8
    }
}
