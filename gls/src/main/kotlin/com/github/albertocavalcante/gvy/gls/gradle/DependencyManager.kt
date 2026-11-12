package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolFileWatcher
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.ResolutionCodes
import com.github.albertocavalcante.gvy.build.ResolutionStatus
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import com.github.albertocavalcante.gvy.build.gradle.GradleFailureAnalyzer
import com.github.albertocavalcante.gvy.build.maven.MavenBuildTool
import com.github.albertocavalcante.gvy.build.maven.MavenFailureAnalyzer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

private const val PROGRESS_CONNECTING = 25
private const val PROGRESS_RESOLVING = 75
private const val PROGRESS_COMPLETE = 100

private fun resolutionFailure(error: Exception): ResolutionStatus.Failed = ResolutionStatus.Failed(
    ResolutionCodes.DEPENDENCY_RESOLUTION_FAILED,
    error.message ?: "Resolution failed",
    error,
)

/**
 * Asynchronous dependency resolution with retryable failures and publication fenced by attempt identity.
 * With a [targetObserver], a usable resolution is published only after its target evidence is observed.
 */
class DependencyManager(
    private val buildToolManager: BuildToolManager,
    private val scope: CoroutineScope,
    private val targetObserver: TargetEvidenceObserver? = null,
) {
    enum class State {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    private data class Request(
        val workspaceRoot: Path,
        val onProgress: ((Int, String) -> Unit)?,
        val onComplete: (WorkspaceResolution) -> Unit,
        val onError: ((Exception) -> Unit)?,
    )

    private data class Attempt(val generation: Long, val request: Request, val enableFileWatching: Boolean)

    private val logger = KotlinLogging.logger {}
    private val lock = Any()
    private val state = AtomicReference(State.NOT_STARTED)
    private val publishedStatusRef = AtomicReference<ResolutionStatus?>(null)
    private val dependencies = AtomicReference<List<Path>>(emptyList())
    private val sourceDirs = AtomicReference<List<Path>>(emptyList())
    private var generation = 0L
    private var currentAttempt: Attempt? = null
    private var resolutionJob: Job? = null
    private var buildFileWatcher: BuildToolFileWatcher? = null
    private var watcherToken: Any? = null

    @Volatile
    private var currentWorkspaceRoot: Path? = null

    @Volatile
    private var currentBuildToolName: String? = null

    /**
     * Starts a new attempt after failure, or for a changed workspace.
     * Duplicate active/successful requests are ignored.
     */
    fun startAsyncResolution(
        workspaceRoot: Path,
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: (WorkspaceResolution) -> Unit,
        onError: ((Exception) -> Unit)? = null,
        enableFileWatching: Boolean = true,
    ) {
        synchronized(lock) {
            if (currentWorkspaceRoot == workspaceRoot &&
                state.get() in setOf(State.IN_PROGRESS, State.COMPLETED)
            ) {
                return
            }
            if (currentWorkspaceRoot != workspaceRoot) {
                stopWatching()
                dependencies.set(emptyList())
                sourceDirs.set(emptyList())
                currentBuildToolName = null
            }
            generation++
            resolutionJob?.cancel()
            currentWorkspaceRoot = workspaceRoot
            publishedStatusRef.set(null)
            state.set(State.IN_PROGRESS)
            val attempt =
                Attempt(generation, Request(workspaceRoot, onProgress, onComplete, onError), enableFileWatching)
            currentAttempt = attempt
            // Register before starting, including when the caller uses an immediate dispatcher.
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { resolve(attempt) }
            resolutionJob = job
            notifyCurrent(attempt) { onProgress?.invoke(0, "Starting dependency resolution...") }
            job.start()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Build tool implementations expose failures as arbitrary exceptions.
    private suspend fun resolve(attempt: Attempt) {
        val request = attempt.request
        var tool: BuildTool? = null
        try {
            checkCurrent(attempt)
            tool = buildToolManager.detectBuildTool(request.workspaceRoot)
            checkCurrent(attempt)
            notifyCurrent(attempt) { currentBuildToolName = tool?.name }
            // Install before resolving: an initially broken build must still be able to recover on an edit.
            if (tool != null && attempt.enableFileWatching) startWatching(attempt, tool)
            notifyCurrent(attempt) {
                request.onProgress?.invoke(PROGRESS_CONNECTING, "Connecting to ${tool?.name ?: "workspace"}...")
            }
            checkCurrent(attempt)
            val legacy = tool?.resolve(request.workspaceRoot) { message ->
                notifyCurrent(attempt) { request.onProgress?.invoke(PROGRESS_CONNECTING, message) }
            } ?: WorkspaceResolution(emptyList(), listOf(request.workspaceRoot))
            checkCurrent(attempt)
            val resolution = legacy.withObservedTargets(targetObserver, tool, request.workspaceRoot, attempt.generation)
            checkCurrent(attempt)
            notifyCurrent(attempt) {
                if (resolution.isUsable) {
                    dependencies.set(resolution.dependencies)
                    sourceDirs.set(resolution.sourceDirectories)
                }
                request.onProgress?.invoke(PROGRESS_RESOLVING, "Found ${resolution.dependencies.size} dependencies")
                request.onProgress?.invoke(PROGRESS_COMPLETE, "Dependency resolution finished")
            }
            withContext(Dispatchers.Default) {
                notifyCurrent(attempt) {
                    request.onComplete(resolution)
                    if (generation == attempt.generation) {
                        publishedStatusRef.set(resolution.status)
                        state.set(if (resolution.isUsable) State.COMPLETED else State.FAILED)
                    }
                }
            }
        } catch (e: CancellationException) {
            notifyCurrent(attempt) { if (state.get() == State.IN_PROGRESS) state.set(State.NOT_STARTED) }
            throw e
        } catch (e: Exception) {
            notifyCurrent(attempt) {
                logger.error(e) { "Dependency resolution failed for ${request.workspaceRoot}" }
                publishedStatusRef.set(resolutionFailure(e))
                state.set(State.FAILED)
            }
            withContext(Dispatchers.Default) {
                notifyCurrent(attempt) {
                    if (tool != null) {
                        request.onComplete(classifyFailure(tool, e))
                    } else {
                        request.onError?.invoke(e)
                            ?: logger.warn { "No error handler for dependency resolution failure" }
                    }
                }
            }
        } finally {
            notifyCurrent(attempt) { resolutionJob = null }
        }
    }

    private fun classifyFailure(tool: BuildTool, error: Exception): WorkspaceResolution {
        val status = if (tool is MavenBuildTool) {
            MavenFailureAnalyzer().classifyException(error, groovyVersion = null)
        } else {
            GradleFailureAnalyzer().classifyException(error)
        }
        return WorkspaceResolution(emptyList(), emptyList(), status)
    }

    private suspend fun checkCurrent(attempt: Attempt) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (generation != attempt.generation) throw CancellationException("Dependency attempt superseded")
        }
    }

    /** Keep the generation check and its effects atomic with cancellation/reset/new attempts. */
    private fun notifyCurrent(attempt: Attempt, action: () -> Unit) {
        synchronized(lock) {
            if (generation == attempt.generation) action()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Watching failure should not prevent dependency resolution.
    private fun startWatching(attempt: Attempt, tool: BuildTool) {
        notifyCurrent(attempt) {
            if (buildFileWatcher == null) {
                val token = Any()
                try {
                    val watcher = tool.createWatcher(scope) { refreshFromWatcher(token) }
                    buildFileWatcher = watcher
                    watcherToken = token
                    watcher?.startWatching(attempt.request.workspaceRoot)
                } catch (e: CancellationException) {
                    stopWatching()
                    throw e
                } catch (e: Exception) {
                    stopWatching()
                    logger.warn(e) { "Failed to watch build files" }
                    attempt.request.onError?.invoke(e)
                }
            }
        }
    }

    private fun refreshFromWatcher(token: Any) {
        synchronized(lock) {
            if (watcherToken !== token) return
            val request = currentAttempt?.request ?: return
            state.set(State.NOT_STARTED)
            startAsyncResolution(
                request.workspaceRoot,
                request.onProgress,
                request.onComplete,
                request.onError,
                enableFileWatching = false,
            )
        }
    }

    private fun stopWatching() {
        watcherToken = null
        buildFileWatcher?.stopWatching()
        buildFileWatcher = null
    }

    fun getCurrentDependencies(): List<Path> = dependencies.get()
    fun getCurrentSourceDirectories(): List<Path> = sourceDirs.get()
    fun getState(): State = state.get()

    /** Terminal attempt status; success is visible only after workspace publication. */
    val publishedStatus: ResolutionStatus?
        get() = publishedStatusRef.get()
    fun getCurrentWorkspaceRoot(): Path? = currentWorkspaceRoot
    fun getCurrentBuildToolName(): String? = currentBuildToolName
    fun isDependenciesReady(): Boolean = state.get() == State.COMPLETED

    fun cancel() {
        synchronized(lock) {
            generation++
            resolutionJob?.cancel()
            resolutionJob = null
            stopWatching()
            if (state.get() == State.IN_PROGRESS) state.set(State.NOT_STARTED)
        }
    }

    fun reset() {
        synchronized(lock) {
            cancel()
            dependencies.set(emptyList())
            sourceDirs.set(emptyList())
            publishedStatusRef.set(null)
            state.set(State.NOT_STARTED)
            currentWorkspaceRoot = null
            currentBuildToolName = null
            currentAttempt = null
        }
    }

    fun getStatusSummary(): String =
        "DependencyManager[state=${state.get()}, tool=${currentBuildToolName ?: "none"}, " +
            "dependencies=${dependencies.get().size}, sources=${sourceDirs.get().size}, " +
            "workspace=${currentWorkspaceRoot?.fileName ?: "none"}]"
}
