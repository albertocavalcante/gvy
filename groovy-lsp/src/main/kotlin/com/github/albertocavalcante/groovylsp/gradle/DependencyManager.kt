package com.github.albertocavalcante.groovylsp.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolFileWatcher
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import com.github.albertocavalcante.gvy.build.gradle.GradleFailureAnalyzer
import com.github.albertocavalcante.gvy.build.maven.MavenBuildTool
import com.github.albertocavalcante.gvy.build.maven.MavenFailureAnalyzer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

private const val PROGRESS_CONNECTING = 25
private const val PROGRESS_RESOLVING = 75
private const val PROGRESS_COMPLETE = 100

/**
 * Manages asynchronous dependency resolution to prevent blocking LSP initialization.
 * Allows for non-blocking dependency discovery with state tracking and callback support.
 */
class DependencyManager(private val buildToolManager: BuildToolManager, private val scope: CoroutineScope) {
    /**
     * States of dependency resolution process.
     */
    enum class State {
        NOT_STARTED, // No resolution attempted yet
        IN_PROGRESS, // Currently resolving dependencies
        COMPLETED, // Successfully resolved dependencies
        FAILED, // Resolution failed (will retry later)
    }

    private val logger = KotlinLogging.logger {}

    private val state = AtomicReference(State.NOT_STARTED)
    private val dependencies = AtomicReference<List<Path>>(emptyList())
    private val sourceDirs = AtomicReference<List<Path>>(emptyList())
    private var resolutionJob: Job? = null
    private var currentWorkspaceRoot: Path? = null
    private var currentBuildToolName: String? = null

    // Build file watching
    private var buildFileWatcher: BuildToolFileWatcher? = null

    /**
     * Starts asynchronous dependency resolution for the given workspace.
     *
     * @param workspaceRoot The root directory of the workspace to resolve dependencies for
     * @param onProgress Optional callback for progress updates (percentage, message)
     * @param onComplete Callback invoked when resolution completes successfully
     * @param onError Optional callback invoked if resolution fails
     */
    // FIXME: Replace with specific exception types (IOException, GradleConnectionException)
    @Suppress("TooGenericExceptionCaught")
    fun startAsyncResolution(
        workspaceRoot: Path,
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: (WorkspaceResolution) -> Unit,
        onError: ((Exception) -> Unit)? = null,
        enableFileWatching: Boolean = true,
    ) {
        // TODO(#833): Allow retries after FAILED state instead of blocking indefinitely.
        //   See: https://github.com/albertocavalcante/gvy/issues/833
        // Only start if not already running or if workspace changed
        if (state.compareAndSet(State.NOT_STARTED, State.IN_PROGRESS) ||
            (currentWorkspaceRoot != workspaceRoot && state.get() != State.IN_PROGRESS)
        ) {
            currentWorkspaceRoot = workspaceRoot

            // Cancel any existing job
            resolutionJob?.cancel()

            logger.info { "Starting async dependency resolution for: $workspaceRoot" }
            onProgress?.invoke(0, "Starting dependency resolution...")

            resolutionJob = scope.launch(Dispatchers.IO) {
                var detectedBuildTool: BuildTool? = null
                try {
                    detectedBuildTool = buildToolManager.detectBuildTool(workspaceRoot)

                    if (detectedBuildTool == null) {
                        logger.info { "No supported build tool detected for: $workspaceRoot" }
                        onProgress?.invoke(PROGRESS_COMPLETE, "No build tool detected")

                        val emptyResolution = WorkspaceResolution(emptyList(), listOf(workspaceRoot))
                        dependencies.set(emptyResolution.dependencies)
                        sourceDirs.set(emptyResolution.sourceDirectories)
                        state.set(State.COMPLETED)
                        currentBuildToolName = null

                        withContext(Dispatchers.Default) {
                            onComplete(emptyResolution)
                        }
                        return@launch
                    }

                    currentBuildToolName = detectedBuildTool.name
                    logger.info { "Detected build tool: ${detectedBuildTool.name}" }

                    onProgress?.invoke(PROGRESS_CONNECTING, "Connecting to ${detectedBuildTool.name}...")

                    // Pass download progress through to resolver (e.g., Gradle distribution download)
                    val resolution = detectedBuildTool.resolve(
                        workspaceRoot = workspaceRoot,
                        onProgress = { message ->
                            // Forward download progress to the onProgress callback
                            onProgress?.invoke(PROGRESS_CONNECTING, message)
                        },
                    )

                    onProgress?.invoke(PROGRESS_RESOLVING, "Found ${resolution.dependencies.size} dependencies")

                    // Update atomic state
                    dependencies.set(resolution.dependencies)
                    sourceDirs.set(resolution.sourceDirectories)
                    state.set(State.COMPLETED)

                    onProgress?.invoke(PROGRESS_COMPLETE, "Dependencies resolved: ${resolution.dependencies.size} JARs")

                    // Call completion callback on Default dispatcher
                    withContext(Dispatchers.Default) {
                        onComplete(resolution)
                    }

                    // Start build file watching if enabled
                    if (enableFileWatching) {
                        tryStartBuildFileWatching(detectedBuildTool, workspaceRoot, onProgress, onComplete, onError)
                    }

                    logger.info {
                        "Async dependency resolution completed: ${resolution.dependencies.size} dependencies, " +
                            "${resolution.sourceDirectories.size} source directories"
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Async dependency resolution failed for $workspaceRoot" }
                    state.set(State.FAILED)

                    withContext(Dispatchers.Default) {
                        // Only classify build tool errors. For truly unexpected errors (like NPE during detection),
                        // use the error callback
                        if (detectedBuildTool != null) {
                            // Classify the exception using the appropriate failure analyzer
                            val classifiedStatus = when (detectedBuildTool) {
                                is MavenBuildTool -> {
                                    val mavenAnalyzer = MavenFailureAnalyzer()
                                    mavenAnalyzer.classifyException(e, groovyVersion = null)
                                }
                                else -> {
                                    // For Gradle and other build tools, use GradleFailureAnalyzer
                                    val gradleAnalyzer = GradleFailureAnalyzer()
                                    gradleAnalyzer.classifyException(e)
                                }
                            }

                            // Create a WorkspaceResolution with the failed status
                            val failedResolution = WorkspaceResolution(
                                dependencies = emptyList(),
                                sourceDirectories = emptyList(),
                                status = classifiedStatus,
                            )

                            // Call onComplete with the failed resolution for classified build tool errors
                            onComplete(failedResolution)
                        } else {
                            // For truly unexpected errors (e.g., NPE during build tool detection),
                            // call onError if provided, otherwise log warning
                            onError?.invoke(e) ?: run {
                                logger.warn { "No error handler provided for dependency resolution failure" }
                            }
                        }
                    }
                }
            }
        } else {
            logger.debug { "Dependency resolution already in progress or completed for: $workspaceRoot" }
        }
    }

    /**
     * Gets the currently resolved dependencies (may be empty if not ready).
     */
    fun getCurrentDependencies(): List<Path> = dependencies.get()

    fun getCurrentSourceDirectories(): List<Path> = sourceDirs.get()

    /**
     * Gets the current state of dependency resolution.
     */
    fun getState(): State = state.get()

    /**
     * Gets the workspace root currently being processed.
     */
    fun getCurrentWorkspaceRoot(): Path? = currentWorkspaceRoot

    /**
     * Gets the name of the currently active build tool.
     */
    fun getCurrentBuildToolName(): String? = currentBuildToolName

    /**
     * Checks if dependencies are ready for use.
     */
    fun isDependenciesReady(): Boolean = state.get() == State.COMPLETED

    /**
     * Cancels any ongoing dependency resolution.
     */
    fun cancel() {
        resolutionJob?.cancel()
        buildFileWatcher?.stopWatching()
        if (state.get() == State.IN_PROGRESS) {
            state.set(State.NOT_STARTED)
        }
        logger.debug { "Dependency resolution cancelled" }
    }

    /**
     * Resets the dependency manager to allow fresh resolution.
     * Useful when build files change or workspace is reloaded.
     */
    fun reset() {
        cancel()
        dependencies.set(emptyList())
        sourceDirs.set(emptyList())
        state.set(State.NOT_STARTED)
        currentWorkspaceRoot = null
        currentBuildToolName = null
        buildFileWatcher = null
        logger.debug { "Dependency manager reset" }
    }

    /**
     * Gets a summary of the current state for debugging/logging.
     */
    fun getStatusSummary(): String {
        val currentState = state.get()
        val depCount = dependencies.get().size
        val sourceCount = sourceDirs.get().size
        val workspace = currentWorkspaceRoot?.fileName ?: "none"
        val tool = currentBuildToolName ?: "none"

        return "DependencyManager[state=$currentState, tool=$tool, dependencies=$depCount, sources=$sourceCount, " +
            "workspace=$workspace]"
    }

    private fun tryStartBuildFileWatching(
        buildTool: BuildTool,
        workspaceRoot: Path,
        onProgress: ((Int, String) -> Unit)?,
        onComplete: (WorkspaceResolution) -> Unit,
        onError: ((Exception) -> Unit)?,
    ) {
        try {
            buildFileWatcher = buildTool.createWatcher(
                coroutineScope = scope,
                onChange = { changedProjectDir ->
                    logger.info { "Build file changed, refreshing dependencies for: $changedProjectDir" }
                    onProgress?.invoke(0, "Build file changed, refreshing dependencies...")

                    // Reset state to trigger fresh resolution
                    state.set(State.NOT_STARTED)

                    // Start fresh resolution
                    startAsyncResolution(
                        workspaceRoot = changedProjectDir,
                        onProgress = onProgress,
                        onComplete = onComplete,
                        onError = onError,
                        enableFileWatching = false, // Avoid recursive watching
                    )
                },
            )

            buildFileWatcher?.startWatching(workspaceRoot)
            if (buildFileWatcher != null) {
                logger.info { "Started build file watching for: $workspaceRoot using ${buildTool.name}" }
            } else {
                logger.info { "Build file watching not supported by ${buildTool.name}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to start build file watching" }
            onError?.invoke(e)
        }
    }
}
