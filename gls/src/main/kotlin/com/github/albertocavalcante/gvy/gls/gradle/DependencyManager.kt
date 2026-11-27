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
import com.github.albertocavalcante.gvy.concurrent.OnceFlag
import com.github.albertocavalcante.gvy.concurrent.SharedValue
import com.github.albertocavalcante.gvy.source.AnalysisGeneration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val PROGRESS_CONNECTING = 25
private const val PROGRESS_RESOLVING = 75
private const val PROGRESS_COMPLETE = 100

/**
 * What one resolution request reports to its caller. Each callback runs under the manager's lock, serialized with
 * lifecycle transitions, and must follow [DependencyManager]'s rules for callbacks.
 *
 * [onCancelled] runs when the current attempt of the request stops before it delivered a completion or failure and
 * no later attempt carries the request on: [DependencyManager.cancel], [DependencyManager.reset], cancellation of the
 * owning scope, an attempt for another request taking over, a build-file refresh that found the watcher gone after
 * signalling the attempt, or an [Error] thrown out of the attempt. A build-file refresh carries the request on, so its
 * attempt's completion or failure ends the request instead.
 *
 * Each attempt ends at most once, but a request can have several attempts: it may complete and then be cancelled
 * during a refresh, or be cancelled twice when a refresh races scope cancellation. [onCancelled] must therefore be
 * idempotent, and safe after [onComplete] or [onError].
 */
internal data class ResolutionCallbacks(
    val onProgress: ((Int, String) -> Unit)? = null,
    val onComplete: (WorkspaceResolution) -> Unit,
    val onError: ((Exception) -> Unit)? = null,
    val onCancelled: (() -> Unit)? = null,
)

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

    private data class Request(val workspaceRoot: Path, val callbacks: ResolutionCallbacks)

    private data class Attempt(
        val generation: AnalysisGeneration,
        val request: Request,
        val enableFileWatching: Boolean,
        val cancellation: ResolutionCancellation = ResolutionCancellation(),
    ) {
        /** Set under the manager's lock once this attempt ended its request: completed, failed or cancelled. */
        val settled = OnceFlag()
    }

    /** A watcher and the token its refreshes carry, published as one value so a reader sees both or neither. */
    private class InstalledWatcher(val watcher: BuildToolFileWatcher, val token: Any)

    private val logger = KotlinLogging.logger {}
    private val lock = Any()
    private val state = SharedValue(State.NOT_STARTED)
    private var generation = AnalysisGeneration.INITIAL

    private val currentAttempt = SharedValue<Attempt?>(null)
    private var resolutionJob: Job? = null

    // Written only under [lock]. Read without it by onWatchedFilesChanged, and by prepareStart's pre-check, which
    // installAttempt repeats under the lock.
    private val installedWatcher = SharedValue<InstalledWatcher?>(null)

    private val currentWorkspaceRoot = SharedValue<Path?>(null)

    private val currentBuildToolName = SharedValue<String?>(null)

    /**
     * Starts a new attempt after failure, or for a changed workspace, reporting to [callbacks].
     * A duplicate of the active or successful request for the same workspace is ignored, and none of its [callbacks]
     * ever runs.
     */
    internal fun startAsyncResolution(
        workspaceRoot: Path,
        callbacks: ResolutionCallbacks,
        enableFileWatching: Boolean = true,
    ) {
        start(Request(workspaceRoot, callbacks), enableFileWatching, expectedWatcher = null)
    }

    private data class StartEffects(
        val attempt: Attempt,
        val job: Job,
        val previousJob: Job?,
        val previousWatcher: BuildToolFileWatcher?,
        /** The attempt of another request this one replaced; nothing else would end that request. */
        val replaced: Attempt? = null,
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
        val expected = currentAttempt.value
        val activeRequest = when {
            expectedWatcher == null -> requireNotNull(request)
            installedWatcher.value?.token !== expectedWatcher -> null
            else -> expected?.request
        } ?: return StartDecision.Stop
        val possibleDuplicate = expectedWatcher == null &&
            expected?.request?.workspaceRoot == activeRequest.workspaceRoot &&
            state.value in setOf(State.IN_PROGRESS, State.COMPLETED) &&
            !expected.cancellation.isCancelled
        if (possibleDuplicate) {
            val duplicate = synchronized(lock) {
                currentAttempt.value === expected &&
                    currentWorkspaceRoot.value == activeRequest.workspaceRoot &&
                    state.value in setOf(State.IN_PROGRESS, State.COMPLETED) &&
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
        if (currentAttempt.value !== expected) return@synchronized StartDecision.Retry
        val staleWatcher = expectedWatcher != null && installedWatcher.value?.token !== expectedWatcher
        if (staleWatcher) {
            // prepareStart already signalled the current attempt, which therefore delivers nothing; no attempt follows
            expected?.let(::cancelAttempt)
            return@synchronized StartDecision.Stop
        }
        // Bumped before any mutation: next() throws on overflow, which must not leave the attempt half-installed.
        generation = generation.next()
        val rootChanged = currentWorkspaceRoot.value != request.workspaceRoot
        // Invalidate the old watcher before publishing the new attempt, so a stale event cannot pair
        // its token with an attempt from another workspace.
        val watcher = if (rootChanged) detachWatcher() else null
        if (rootChanged) currentBuildToolName.set(null)
        val previousJob = resolutionJob
        currentWorkspaceRoot.set(request.workspaceRoot)
        state.set(State.IN_PROGRESS)
        val attempt = Attempt(generation, request, enableFileWatching)
        currentAttempt.set(attempt)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { resolve(attempt) }
        resolutionJob = job
        val replaced = expected?.takeIf { it.request !== request }
        StartDecision.Ready(StartEffects(attempt, job, previousJob, watcher, replaced))
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun launchAttempt(effects: StartEffects) {
        cancelJob(effects.previousJob)
        stopWatcher(effects.previousWatcher)
        // Cancellation must signal the transport as soon as a parent starts cancelling, not after resolve returns.
        effects.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) effects.attempt.cancellation.cancel()
        }
        effects.job.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (currentAttempt.value === effects.attempt) {
                    if (state.value == State.IN_PROGRESS) state.set(State.NOT_STARTED)
                    if (resolutionJob === effects.job) resolutionJob = null
                    // An Error escaped resolve, or the owning scope was cancelled: no attempt can follow. Any other
                    // cancellation of a current attempt comes from a refresh, which carries the request on, or from
                    // stop(), which ends the request itself.
                    if (cause != null && (cause !is CancellationException || !scope.isActive)) {
                        cancelAttempt(effects.attempt)
                    }
                }
            }
        }
        notifyCurrent(effects.attempt) {
            effects.attempt.request.callbacks.onProgress?.invoke(0, "Starting dependency resolution...")
        }
        effects.job.start()
        // Ended last, once the new attempt runs: an attempt of another request was replaced and nothing follows it
        effects.replaced?.let { replaced -> synchronized(lock) { cancelAttempt(replaced) } }
    }

    @Suppress("TooGenericExceptionCaught") // Build tool implementations expose failures as arbitrary exceptions.
    private suspend fun resolve(attempt: Attempt) {
        val request = attempt.request
        val callbacks = request.callbacks
        var tool: BuildTool? = null
        try {
            checkCurrent(attempt)
            tool = buildToolManager.detectBuildTool(request.workspaceRoot)
            checkCurrent(attempt)
            notifyCurrent(attempt) { currentBuildToolName.set(tool?.name) }
            // Install before resolving: an initially broken build must still be able to recover on an edit.
            if (tool != null && attempt.enableFileWatching) startWatching(attempt, tool)
            notifyCurrent(attempt) {
                callbacks.onProgress?.invoke(PROGRESS_CONNECTING, "Connecting to ${tool?.name ?: "workspace"}...")
            }
            checkCurrent(attempt)
            val legacy = tool?.resolve(
                request.workspaceRoot,
                { message -> notifyCurrent(attempt) { callbacks.onProgress?.invoke(PROGRESS_CONNECTING, message) } },
                attempt.cancellation,
            ) ?: WorkspaceResolution(emptyList(), listOf(request.workspaceRoot))
            checkCurrent(attempt)
            val resolution = legacy.withObservedTargets(targetObserver, tool, request.workspaceRoot, attempt.generation)
            checkCurrent(attempt)
            notifyCurrent(attempt) { state.set(if (resolution.isUsable) State.COMPLETED else State.FAILED) }
            notifyCurrent(attempt) {
                callbacks.onProgress?.invoke(PROGRESS_RESOLVING, "Found ${resolution.dependencies.size} dependencies")
            }
            notifyCurrent(attempt) { callbacks.onProgress?.invoke(PROGRESS_COMPLETE, "Dependency resolution finished") }
            withContext(Dispatchers.Default) {
                settleCurrent(attempt) { callbacks.onComplete(resolution) }
            }
        } catch (e: CancellationException) {
            notifyCurrent(attempt) {
                if (state.value == State.IN_PROGRESS) state.set(State.NOT_STARTED)
            }
            throw e
        } catch (e: Exception) {
            notifyCurrent(attempt) {
                logger.error(e) { "Dependency resolution failed for ${request.workspaceRoot}" }
                state.set(State.FAILED)
            }
            withContext(Dispatchers.Default) {
                settleCurrent(attempt) {
                    if (tool != null) {
                        callbacks.onComplete(classifyFailure(tool, e))
                    } else {
                        callbacks.onError?.invoke(e)
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

    /** Delivers [attempt]'s completion or failure while it is current, recording that the attempt ended its request. */
    private fun settleCurrent(attempt: Attempt, deliver: () -> Unit) = notifyCurrent(attempt) {
        attempt.settled.set()
        deliver()
    }

    /**
     * Ends [attempt]'s request as cancelled unless the attempt already ended it. Called under [lock], like every
     * other callback; a failing callback is logged, since it must not break the transition that ends the attempt.
     */
    @Suppress("TooGenericExceptionCaught") // Callbacks may throw anything; the lifecycle transition must finish.
    private fun cancelAttempt(attempt: Attempt) {
        if (!attempt.settled.trySet()) return
        try {
            attempt.request.callbacks.onCancelled?.invoke()
        } catch (failure: Exception) {
            logger.warn(failure) { "Dependency cancellation callback failed" }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Watching failure should not prevent dependency resolution.
    private fun startWatching(attempt: Attempt, tool: BuildTool) {
        val token = Any()
        var watcher: BuildToolFileWatcher? = null
        try {
            watcher = tool.createWatcher(scope) { refreshFromWatcher(token) } ?: return
            val installed = synchronized(lock) {
                val superseded = generation != attempt.generation || attempt.cancellation.isCancelled
                if (superseded || installedWatcher.value != null) {
                    false
                } else {
                    installedWatcher.set(InstalledWatcher(watcher, token))
                    true
                }
            }
            if (!installed) {
                watcher.stopWatching()
                return
            }
            watcher.startWatching(attempt.request.workspaceRoot)
            if (synchronized(lock) { installedWatcher.value?.token !== token }) watcher.stopWatching()
        } catch (e: CancellationException) {
            discardWatcher(token, watcher)
            throw e
        } catch (e: Exception) {
            discardWatcher(token, watcher)
            logger.warn(e) { "Failed to watch build files" }
            settleCurrent(attempt) { attempt.request.callbacks.onError?.invoke(e) }
        }
    }

    private fun discardWatcher(token: Any, watcher: BuildToolFileWatcher?) {
        synchronized(lock) { if (installedWatcher.value?.token === token) detachWatcher() }
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
    private fun detachWatcher(): BuildToolFileWatcher? = installedWatcher.exchange(null)?.watcher

    /**
     * Routes client notifications through the native watcher's debounce and filtering.
     *
     * The LSP message thread calls this, so it reads the watcher without [lock]: a completion callback holds the lock
     * while it rebuilds the workspace. The read can return a watcher that a transition has just detached but not yet
     * stopped. That watcher drops the event, or debounces it into a refresh whose revoked token [start] rejects.
     */
    fun onWatchedFilesChanged(paths: List<Path>) {
        val watcher = installedWatcher.value?.watcher ?: return
        paths.forEach { watcher.onFileChanged(it) }
    }

    fun getState(): State = state.value
    fun getCurrentBuildToolName(): String? = currentBuildToolName.value
    fun isDependenciesReady(): Boolean = state.value == State.COMPLETED

    fun cancel() = stop(reset = false)

    fun reset() = stop(reset = true)

    private fun stop(reset: Boolean) {
        check(!Thread.holdsLock(lock)) { "Lifecycle calls cannot run from a dependency callback" }
        while (true) {
            val expected = currentAttempt.value
            expected?.cancellation?.cancel()
            val effects = synchronized(lock) {
                if (currentAttempt.value !== expected) return@synchronized null
                generation = generation.next()
                val oldJob = resolutionJob
                resolutionJob = null
                val watcher = detachWatcher()
                if (state.value == State.IN_PROGRESS) state.set(State.NOT_STARTED)
                if (reset) {
                    state.set(State.NOT_STARTED)
                    currentWorkspaceRoot.set(null)
                    currentBuildToolName.set(null)
                    currentAttempt.set(null)
                }
                // Stopped, last in the transition: no later attempt carries its request on
                expected?.let(::cancelAttempt)
                oldJob to watcher
            } ?: continue
            cancelJob(effects.first)
            stopWatcher(effects.second)
            return
        }
    }
}
