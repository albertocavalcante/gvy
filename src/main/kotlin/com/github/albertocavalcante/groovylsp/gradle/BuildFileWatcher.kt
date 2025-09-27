package com.github.albertocavalcante.groovylsp.gradle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
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
 * Watches Gradle build files for changes and triggers dependency re-resolution.
 * Monitors build.gradle, build.gradle.kts, settings.gradle, and settings.gradle.kts files.
 */
class BuildFileWatcher(
    private val coroutineScope: CoroutineScope,
    private val onBuildFileChanged: (Path) -> Unit,
    private val debounceDelayMs: Long = FILE_CHANGE_DELAY_MS,
) {
    private val logger = LoggerFactory.getLogger(BuildFileWatcher::class.java)
    private var watchService: WatchService? = null
    private var watchJob: Job? = null
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()

    private val buildFileNames = setOf(
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
    )

    /**
     * Starts watching the given project directory for build file changes.
     */
    // FIXME: Replace with specific exception types (IOException, ClosedWatchServiceException)
    @Suppress("TooGenericExceptionCaught")
    fun startWatching(projectDir: Path) {
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
        } catch (e: Exception) {
            logger.error("Failed to start build file watcher", e)
        }
    }

    /**
     * Stops watching for build file changes.
     */
    // FIXME: Replace with specific exception types (IOException, ClosedWatchServiceException)
    @Suppress("TooGenericExceptionCaught")
    fun stopWatching() {
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
        } catch (e: Exception) {
            logger.warn("Error stopping build file watcher", e)
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
                // Poll for events with timeout
                val watchKey = try {
                    // Use a timeout to allow periodic cancellation checks
                    watchService.poll(
                        java.util.concurrent.TimeUnit.SECONDS.toMillis(1),
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                } catch (e: InterruptedException) {
                    logger.debug("Watch service interrupted")
                    break
                }

                if (watchKey == null) {
                    continue // Timeout, check if still active
                }

                val projectDir = watchKeys[watchKey]
                if (projectDir == null) {
                    logger.warn("Unknown watch key, skipping events")
                    watchKey.reset()
                    continue
                }

                // Process events
                for (event in watchKey.pollEvents()) {
                    processWatchEvent(event, projectDir)
                }

                // Reset the key for next iteration
                val valid = watchKey.reset()
                if (!valid) {
                    logger.warn("Watch key no longer valid for: $projectDir")
                    watchKeys.remove(watchKey)
                    break
                }
            }
        } catch (e: Exception) {
            logger.error("Error in build file watch loop", e)
        }
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
                    } catch (e: Exception) {
                        logger.error("Error handling build file change for $filenameStr", e)
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
    fun isWatching(): Boolean = watchJob?.isActive == true
}
