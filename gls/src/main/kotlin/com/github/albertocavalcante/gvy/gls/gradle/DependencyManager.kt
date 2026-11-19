// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolFileWatcher
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.ResolutionCancellation
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import com.github.albertocavalcante.gvy.build.gradle.GradleFailureAnalyzer
import com.github.albertocavalcante.gvy.build.maven.MavenBuildTool
import com.github.albertocavalcante.gvy.build.maven.MavenFailureAnalyzer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
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

/**
 * Asynchronous dependency resolution with retryable failures and publication fenced by attempt identity.
 * With a [targetObserver], a usable resolution is published only after its target evidence is observed.
 *
 * Callbacks are serialized with lifecycle transitions. A callback already admitted may finish before a transition
 * returns; callbacks must not synchronously call lifecycle methods, cancel the owning scope, or wait for a lifecycle
 * thread. Cancellation listeners must return promptly and must not wait for a successor attempt. Jobs launched by a
 * callback are owned by that callback and are not fenced by this manager.
 */
@Suppress("TooManyFunctions") // Coordinates dependency state, resolution, and watcher lifecycle as one facade.
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

    private data class Attempt(
        val generation: Long,
        val request: Request,
        val enableFileWatching: Boolean,
        val cancellation: ResolutionCancellation = ResolutionCancellation(),
    )

    private val logger = KotlinLogging.logger {}
    private val lock = Any()
    private val state = AtomicReference(State.NOT_STARTED)
    private val dependencies = AtomicReference<List<Path>>(emptyList())
    private val sourceDirs = AtomicReference<List<Path>>(emptyList())
    private var generation = 0L

    @Volatile
    private var currentAttempt: Attempt? = null
    private var resolutionJob: Job? = null
    private var buildFileWatcher: BuildToolFileWatcher? = null

    @Volatile
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
        val request = Request(workspaceRoot, onProgress, onComplete, onError)
        start(request, enableFileWatching, expectedWatcher = null)
    }

    private data class StartEffects(
        val attempt: Attempt,
        val job: Job,
        val previousJob: Job?,
        val previousWatcher: BuildToolFileWatcher?,
    )

    private sealed interface StartDecision {
        data object Stop : StartDecision
        data object Retry : StartDecision
        data class Ready(val effects: StartEffects) : StartDecision
    }

    private fun start(request: Request?, enableFileWatching: Boolean, expectedWatcher: Any?) {
        check(!Thread.holdsLock(lock)) { "Lifecycle calls cannot run from a dependency callback" }
        while (true) {
            when (val decision = prepareStart(request, enableFileWatching, expectedWatcher)) {
                StartDecision.Stop -> return
                StartDecision.Retry -> Unit
                is StartDecision.Ready -> {
                    launchAttempt(decision.effects)
                    return
                }
            }
        }
    }

    private fun prepareStart(request: Request?, enableFileWatching: Boolean, expectedWatcher: Any?): StartDecision {
        val expected = currentAttempt
        val activeRequest = when {
            expectedWatcher == null -> requireNotNull(request)
            watcherToken !== expectedWatcher -> null
            else -> expected?.request
        } ?: return StartDecision.Stop
        val possibleDuplicate = expectedWatcher == null &&
            expected?.request?.workspaceRoot == activeRequest.workspaceRoot &&
            state.get() in setOf(State.IN_PROGRESS, State.COMPLETED) &&
            !expected.cancellation.isCancelled
        if (possibleDuplicate) {
            val duplicate = synchronized(lock) {
                currentAttempt === expected &&
                    currentWorkspaceRoot == activeRequest.workspaceRoot &&
                    state.get() in setOf(State.IN_PROGRESS, State.COMPLETED) &&
                    !expected.cancellation.isCancelled
            }
            return if (duplicate) StartDecision.Stop else StartDecision.Retry
        }
        // Signal the captured attempt before waiting for an admitted publication to leave the lock.
        expected?.cancellation?.cancel()
        return installAttempt(expected, activeRequest, enableFileWatching, expectedWatcher)
    }

    private fun installAttempt(
        expected: Attempt?,
        request: Request,
        enableFileWatching: Boolean,
        expectedWatcher: Any?,
    ): StartDecision = synchronized(lock) {
        if (currentAttempt !== expected) return@synchronized StartDecision.Retry
        if (expectedWatcher != null && watcherToken !== expectedWatcher) return@synchronized StartDecision.Stop
        // Invalidate the old watcher before publishing the new attempt, so a stale event cannot pair
        // its token with an attempt from another workspace.
        val watcher = if (currentWorkspaceRoot != request.workspaceRoot) detachWatcher() else null
        if (currentWorkspaceRoot != request.workspaceRoot) {
            dependencies.set(emptyList())
            sourceDirs.set(emptyList())
            currentBuildToolName = null
        }
        generation++
        val previousJob = resolutionJob
        currentWorkspaceRoot = request.workspaceRoot
        state.set(State.IN_PROGRESS)
        val attempt = Attempt(generation, request, enableFileWatching)
        currentAttempt = attempt
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { resolve(attempt) }
        resolutionJob = job
        StartDecision.Ready(StartEffects(attempt, job, previousJob, watcher))
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun launchAttempt(effects: StartEffects) {
        cancelJob(effects.previousJob)
        stopWatcher(effects.previousWatcher)
        // Cancellation must signal the transport as soon as a parent starts cancelling, not after resolve returns.
        effects.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) effects.attempt.cancellation.cancel()
        }
        effects.job.invokeOnCompletion {
            synchronized(lock) {
                if (currentAttempt === effects.attempt) {
                    if (state.get() == State.IN_PROGRESS) state.set(State.NOT_STARTED)
                    if (resolutionJob === effects.job) resolutionJob = null
                }
            }
        }
        notifyCurrent(effects.attempt) {
            effects.attempt.request.onProgress?.invoke(0, "Starting dependency resolution...")
        }
        effects.job.start()
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
            val legacy = tool?.resolve(
                request.workspaceRoot,
                { message -> notifyCurrent(attempt) { request.onProgress?.invoke(PROGRESS_CONNECTING, message) } },
                attempt.cancellation,
            ) ?: WorkspaceResolution(emptyList(), listOf(request.workspaceRoot))
            checkCurrent(attempt)
            val resolution = legacy.withObservedTargets(targetObserver, tool, request.workspaceRoot, attempt.generation)
            checkCurrent(attempt)
            notifyCurrent(attempt) {
                if (resolution.isUsable) {
                    dependencies.set(resolution.dependencies)
                    sourceDirs.set(resolution.sourceDirectories)
                }
                state.set(if (resolution.isUsable) State.COMPLETED else State.FAILED)
            }
            notifyCurrent(attempt) {
                request.onProgress?.invoke(PROGRESS_RESOLVING, "Found ${resolution.dependencies.size} dependencies")
            }
            notifyCurrent(attempt) { request.onProgress?.invoke(PROGRESS_COMPLETE, "Dependency resolution finished") }
            withContext(Dispatchers.Default) {
                notifyCurrent(attempt) { request.onComplete(resolution) }
            }
        } catch (e: CancellationException) {
            notifyCurrent(attempt) {
                if (state.get() == State.IN_PROGRESS) state.set(State.NOT_STARTED)
            }
            throw e
        } catch (e: Exception) {
            notifyCurrent(attempt) {
                logger.error(e) { "Dependency resolution failed for ${request.workspaceRoot}" }
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
            if (generation != attempt.generation || attempt.cancellation.isCancelled) {
                throw CancellationException("Dependency attempt superseded")
            }
        }
    }

    /** Keep the generation check and its effects atomic with cancellation/reset/new attempts. */
    private fun notifyCurrent(attempt: Attempt, action: () -> Unit) {
        synchronized(lock) {
            if (generation == attempt.generation && !attempt.cancellation.isCancelled) action()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Watching failure should not prevent dependency resolution.
    private fun startWatching(attempt: Attempt, tool: BuildTool) {
        val token = Any()
        var watcher: BuildToolFileWatcher? = null
        try {
            watcher = tool.createWatcher(scope) { refreshFromWatcher(token) } ?: return
            val installed = synchronized(lock) {
                if (generation != attempt.generation || attempt.cancellation.isCancelled || buildFileWatcher != null) {
                    false
                } else {
                    buildFileWatcher = watcher
                    watcherToken = token
                    true
                }
            }
            if (!installed) {
                watcher.stopWatching()
                return
            }
            watcher.startWatching(attempt.request.workspaceRoot)
            if (synchronized(lock) { watcherToken !== token }) watcher.stopWatching()
        } catch (e: CancellationException) {
            discardWatcher(token, watcher)
            throw e
        } catch (e: Exception) {
            discardWatcher(token, watcher)
            logger.warn(e) { "Failed to watch build files" }
            notifyCurrent(attempt) { attempt.request.onError?.invoke(e) }
        }
    }

    private fun discardWatcher(token: Any, watcher: BuildToolFileWatcher?) {
        synchronized(lock) { if (watcherToken === token) detachWatcher() }
        watcher?.stopWatching()
    }

    private fun refreshFromWatcher(token: Any) {
        start(request = null, enableFileWatching = false, expectedWatcher = token)
    }

    @Suppress("TooGenericExceptionCaught") // A faulty job cancellation handler must not strand the next attempt.
    private fun cancelJob(job: Job?) {
        try {
            job?.cancel()
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to cancel previous dependency job" }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed old watcher shutdown must not strand the new attempt.
    private fun stopWatcher(watcher: BuildToolFileWatcher?) {
        try {
            watcher?.stopWatching()
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to stop previous build file watcher" }
        }
    }

    /** Called under the state lock; the returned watcher is stopped after releasing it. */
    private fun detachWatcher(): BuildToolFileWatcher? {
        watcherToken = null
        return buildFileWatcher.also { buildFileWatcher = null }
    }

    fun getCurrentDependencies(): List<Path> = dependencies.get()

    /** Routes client notifications through the native watcher's debounce and filtering. */
    fun onWatchedFilesChanged(paths: List<Path>) {
        val watcher = synchronized(lock) { buildFileWatcher }
        paths.forEach { watcher?.onFileChanged(it) }
    }
    fun getCurrentSourceDirectories(): List<Path> = sourceDirs.get()
    fun getState(): State = state.get()
    fun getCurrentWorkspaceRoot(): Path? = currentWorkspaceRoot
    fun getCurrentBuildToolName(): String? = currentBuildToolName
    fun isDependenciesReady(): Boolean = state.get() == State.COMPLETED

    fun cancel() = stop(reset = false)

    fun reset() = stop(reset = true)

    private fun stop(reset: Boolean) {
        check(!Thread.holdsLock(lock)) { "Lifecycle calls cannot run from a dependency callback" }
        while (true) {
            val expected = currentAttempt
            expected?.cancellation?.cancel()
            val effects = synchronized(lock) {
                if (currentAttempt !== expected) return@synchronized null
                generation++
                val oldJob = resolutionJob
                resolutionJob = null
                val watcher = detachWatcher()
                if (state.get() == State.IN_PROGRESS) state.set(State.NOT_STARTED)
                if (reset) {
                    dependencies.set(emptyList())
                    sourceDirs.set(emptyList())
                    state.set(State.NOT_STARTED)
                    currentWorkspaceRoot = null
                    currentBuildToolName = null
                    currentAttempt = null
                }
                oldJob to watcher
            } ?: continue
            cancelJob(effects.first)
            stopWatcher(effects.second)
            return
        }
    }

    fun getStatusSummary(): String =
        "DependencyManager[state=${state.get()}, tool=${currentBuildToolName ?: "none"}, " +
            "dependencies=${dependencies.get().size}, sources=${sourceDirs.get().size}, " +
            "workspace=${currentWorkspaceRoot?.fileName ?: "none"}]"
}
