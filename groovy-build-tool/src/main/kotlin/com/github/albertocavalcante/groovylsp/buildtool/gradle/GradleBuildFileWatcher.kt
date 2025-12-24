package com.github.albertocavalcante.groovylsp.buildtool.gradle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.name

private const val FILE_CHANGE_DELAY_MS = 500L

/**
 * Watches Gradle related build files for changes and triggers dependency re-resolution.
 * Monitors build.gradle, build.gradle.kts, settings.gradle, and settings.gradle.kts files.
 */
class GradleBuildFileWatcher(
    private val coroutineScope: CoroutineScope,
    private val onBuildFileChanged: (Path) -> Unit,
    private val debounceDelayMs: Long = FILE_CHANGE_DELAY_MS,
) : com.github.albertocavalcante.groovylsp.buildtool.BuildToolFileWatcher {
    private val logger = LoggerFactory.getLogger(GradleBuildFileWatcher::class.java)
    private var watchService: WatchService? = null
    private var watchJob: Job? = null
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()

    // TODO: Make this configurable or extendable for other build tools (pom.xml)
    private val buildFileNames = setOf(
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
    )

    private sealed class PollResult {
        object Timeout : PollResult()
        object Interrupted : PollResult()
        data class Success(val watchKey: WatchKey) : PollResult()
    }

    /**
     * Starts watching the given project directory for build file changes.
     */
    // FIXME: Replace with specific exception types (IOException, ClosedWatchServiceException)
    @Suppress("TooGenericExceptionCaught")
    override fun startWatching(projectDir: Path) {
        if (watchJob?.isActive == true) {
            logger.debug("Build file watcher already active for project")
            return
        }

        try {
            watchService = FileSystems.getDefault().newWatchService()

            // Watch the project directory for build file changes
            val watchKey = projectDir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            watchKeys[watchKey] = projectDir

            logger.info("Started watching build files in: $projectDir")

            // Start the watch loop in a coroutine
            watchJob = coroutineScope.launch(Dispatchers.IO) {
                watchLoop()
            }
        } catch (e: IOException) {
            logger.error("IO error starting build file watcher", e)
        } catch (e: UnsupportedOperationException) {
            logger.error("File watching not supported on this platform", e)
        }
    }

    /**
     * Stops watching for build file changes.
     */
    // FIXME: Replace with specific exception types (IOException, ClosedWatchServiceException)
    @Suppress("TooGenericExceptionCaught")
    override fun stopWatching() {
        try {
            watchJob?.cancel()
            watchJob = null

            watchKeys.keys.forEach { key ->
                key.cancel()
            }
            watchKeys.clear()

            watchService?.close()
            watchService = null

            logger.info("Stopped build file watcher")
        } catch (e: IOException) {
            logger.warn("IO error stopping build file watcher", e)
        } catch (e: ClosedWatchServiceException) {
            // Already closed, ignore
        }
    }

    /**
     * Main watch loop that processes file system events.
     */
    // FIXME: Replace with specific exception types (IOException, ClosedWatchServiceException)
    @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
    private suspend fun watchLoop() {
        val watchService = this.watchService ?: return

        try {
            while (coroutineScope.isActive) {
                val result = pollWatchKey(watchService)
                val shouldContinue = handlePollResult(result)
                if (!shouldContinue) {
                    break
                }
            }
        } catch (e: java.nio.file.ClosedWatchServiceException) {
            if (watchJob != null) {
                logger.warn("Watch service closed unexpectedly", e)
                throw e
            }
            logger.debug("Watch service closed, terminating watch loop.")
            return
        } catch (e: IOException) {
            logger.error("IO error in build file watch loop", e)
        } catch (e: InterruptedException) {
            logger.debug("Watch loop interrupted")
        }
    }

    private suspend fun pollWatchKey(watchService: WatchService): PollResult = try {
        val watchKey = watchService.poll(
            java.util.concurrent.TimeUnit.SECONDS.toMillis(1),
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        if (watchKey == null) {
            PollResult.Timeout
        } else {
            PollResult.Success(watchKey)
        }
    } catch (e: InterruptedException) {
        logger.debug("Watch service interrupted")
        PollResult.Interrupted
    }

    private fun handleUnknownWatchKey(watchKey: WatchKey) {
        logger.warn("Unknown watch key, skipping events")
        watchKey.reset()
    }

    private suspend fun handlePollResult(result: PollResult): Boolean = when (result) {
        PollResult.Interrupted -> false
        PollResult.Timeout -> true
        is PollResult.Success -> handleWatchKey(result.watchKey)
    }

    private suspend fun handleWatchKey(watchKey: WatchKey): Boolean {
        val projectDir = watchKeys[watchKey]
        if (projectDir == null) {
            handleUnknownWatchKey(watchKey)
            return true
        }
        return processWatchKeyEvents(watchKey, projectDir)
    }

    private suspend fun processWatchKeyEvents(watchKey: WatchKey, projectDir: Path): Boolean {
        watchKey.pollEvents().forEach { event ->
            processWatchEvent(event, projectDir)
        }

        val stillValid = watchKey.reset()
        if (!stillValid) {
            logger.warn("Watch key no longer valid for: $projectDir")
            watchKeys.remove(watchKey)
        }

        return stillValid
    }

    /**
     * Processes a single file system watch event.
     */
    // FIXME: Replace with specific exception types (IOException, CancellationException)
    @Suppress("TooGenericExceptionCaught")
    private suspend fun processWatchEvent(event: WatchEvent<*>, projectDir: Path) {
        val kind = event.kind()

        if (kind == StandardWatchEventKinds.OVERFLOW) {
            logger.warn("File system watch overflow - some events may have been lost")
            return
        }

        val filename = event.context() as? Path ?: return
        val filenameStr = filename.name

        // Check if this is a build file we care about
        if (!buildFileNames.contains(filenameStr)) {
            return
        }

        val fullPath = projectDir.resolve(filename)

        when (kind) {
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            -> {
                if (fullPath.exists()) {
                    logger.info("Build file changed: $filenameStr")

                    // Add configurable delay to handle rapid successive changes
                    if (debounceDelayMs > 0) {
                        delay(debounceDelayMs)
                    }

                    // Trigger dependency re-resolution
                    try {
                        onBuildFileChanged(projectDir)
                    } catch (e: IOException) {
                        logger.error("IO error handling build file change for $filenameStr", e)
                    } catch (e: CancellationException) {
                        logger.debug("Build file change handling cancelled for $filenameStr")
                    }
                }
            }

            StandardWatchEventKinds.ENTRY_DELETE -> {
                logger.info("Build file deleted: $filenameStr")
                // Could trigger cleanup of cached dependencies here
            }
        }
    }

    /**
     * Checks if the watcher is currently active.
     */
    override fun isWatching(): Boolean = watchJob?.isActive == true
}
