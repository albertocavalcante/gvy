package com.github.albertocavalcante.groovylsp.buildtool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.name

internal const val DEFAULT_BUILD_FILE_CHANGE_DELAY_MS = 500L

/**
 * Default build file watcher implementation shared across build tools.
 *
 * Monitors filesystem changes for specified build files and triggers callbacks
 * when changes are detected. Uses debouncing to avoid excessive callback invocations.
 *
 * @param logLabel Label used in log messages to identify the build tool (e.g., "Gradle", "Maven").
 * @param coroutineScope The coroutine scope for launching watch and debounce jobs.
 * @param onBuildFileChanged Callback invoked when a build file changes. Receives the project directory path.
 * @param buildFileNames Set of build file names to monitor (e.g., "build.gradle", "pom.xml").
 * @param debounceDelayMs Delay in milliseconds before invoking the callback after detecting a change.
 *                        Helps avoid multiple callbacks for rapid successive changes.
 * @param dispatcher The coroutine dispatcher to use for debounce callbacks. Defaults to [Dispatchers.IO].
 *                   Can be injected for testing with [kotlinx.coroutines.test.StandardTestDispatcher].
 *                   Note: The file watch loop always runs on [Dispatchers.IO] as it requires real file system polling.
 */
internal class DefaultBuildFileWatcher(
    private val logLabel: String,
    private val coroutineScope: CoroutineScope,
    private val onBuildFileChanged: (Path) -> Unit,
    private val buildFileNames: Set<String>,
    private val debounceDelayMs: Long = DEFAULT_BUILD_FILE_CHANGE_DELAY_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BuildToolFileWatcher {
    private val logger = LoggerFactory.getLogger(DefaultBuildFileWatcher::class.java)
    private var watchService: WatchService? = null
    private var watchJob: Job? = null
    private var debounceJob: Job? = null
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()

    private sealed class PollResult {
        object Timeout : PollResult()
        object Interrupted : PollResult()
        data class Success(val watchKey: WatchKey) : PollResult()
    }

    @Suppress("TooGenericExceptionCaught")
    override fun startWatching(projectDir: Path) {
        if (watchJob?.isActive == true) {
            logger.debug("{} build file watcher already active for project", logLabel)
            return
        }

        try {
            watchService = FileSystems.getDefault().newWatchService()

            val watchKey = projectDir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            watchKeys[watchKey] = projectDir

            logger.info("Started watching {} build files in: {}", logLabel, projectDir)

            watchJob = coroutineScope.launch(Dispatchers.IO) {
                watchLoop()
            }
        } catch (e: IOException) {
            logger.error("IO error starting {} build file watcher", logLabel, e)
        } catch (e: UnsupportedOperationException) {
            logger.error("File watching not supported on this platform", e)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun stopWatching() {
        try {
            debounceJob?.cancel()
            debounceJob = null

            watchJob?.cancel()
            watchJob = null

            watchKeys.keys.forEach { key ->
                key.cancel()
            }
            watchKeys.clear()

            watchService?.close()
            watchService = null

            logger.info("Stopped {} build file watcher", logLabel)
        } catch (e: IOException) {
            logger.warn("IO error stopping {} build file watcher", logLabel, e)
        } catch (e: ClosedWatchServiceException) {
            // Already closed, ignore - this is expected when stopping an already-stopped watcher
        }
    }

    @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
    private fun watchLoop() {
        val watchService = this.watchService ?: return

        try {
            while (coroutineScope.isActive) {
                val result = pollWatchKey(watchService)
                val shouldContinue = handlePollResult(result)
                if (!shouldContinue) {
                    break
                }
            }
        } catch (e: ClosedWatchServiceException) {
            if (watchJob != null) {
                logger.warn("Watch service closed unexpectedly", e)
                throw e
            } else {
                logger.debug("Watch service closed, terminating watch loop.")
            }
        } catch (e: IOException) {
            logger.error("IO error in {} build file watch loop", logLabel, e)
        } catch (e: Exception) {
            logger.error("Unexpected error in {} build file watch loop", logLabel, e)
        }
    }

    private fun pollWatchKey(watchService: WatchService): PollResult = try {
        val watchKey = watchService.poll(1, TimeUnit.SECONDS)
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

    private fun handlePollResult(result: PollResult): Boolean = when (result) {
        PollResult.Interrupted -> false
        PollResult.Timeout -> true
        is PollResult.Success -> handleWatchKey(result.watchKey)
    }

    private fun handleWatchKey(watchKey: WatchKey): Boolean {
        val projectDir = watchKeys[watchKey]
        if (projectDir == null) {
            handleUnknownWatchKey(watchKey)
            return true
        }
        return processWatchKeyEvents(watchKey, projectDir)
    }

    private fun processWatchKeyEvents(watchKey: WatchKey, projectDir: Path): Boolean {
        watchKey.pollEvents().forEach { event ->
            processWatchEvent(event, projectDir)
        }

        val stillValid = watchKey.reset()
        if (!stillValid) {
            logger.warn("Watch key no longer valid for: {}", projectDir)
            watchKeys.remove(watchKey)
        }

        return stillValid
    }

    @Suppress("TooGenericExceptionCaught")
    private fun processWatchEvent(event: WatchEvent<*>, projectDir: Path) {
        val kind = event.kind()

        if (kind == StandardWatchEventKinds.OVERFLOW) {
            logger.warn("File system watch overflow - some events may have been lost")
            return
        }

        val filename = event.context() as? Path ?: return
        val filenameStr = filename.name

        if (!shouldHandleBuildFile(filenameStr)) {
            return
        }

        val fullPath = projectDir.resolve(filename)
        handleBuildFileEvent(kind, fullPath, filenameStr, projectDir)
    }

    /**
     * Determines if a file should be monitored based on its name.
     *
     * @param filename The name of the file to check.
     * @return `true` if the file is a build file that should be monitored, `false` otherwise.
     */
    private fun shouldHandleBuildFile(filename: String): Boolean = buildFileNames.contains(filename)

    /**
     * Handles different types of build file events (create, modify, delete).
     *
     * @param kind The type of file system event.
     * @param fullPath The full path to the affected file.
     * @param filenameStr The filename string for logging.
     * @param projectDir The project directory path.
     */
    private fun handleBuildFileEvent(kind: WatchEvent.Kind<*>, fullPath: Path, filenameStr: String, projectDir: Path) {
        when (kind) {
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            -> handleBuildFileModification(fullPath, filenameStr, projectDir)

            StandardWatchEventKinds.ENTRY_DELETE -> {
                logger.info("{} build file deleted: {}", logLabel, filenameStr)
            }
        }
    }

    /**
     * Handles build file creation or modification events.
     *
     * Schedules a debounced callback to process the change. If the file doesn't exist,
     * the event is ignored (might be a transient editor operation).
     *
     * @param fullPath The full path to the modified file.
     * @param filenameStr The filename string for logging.
     * @param projectDir The project directory path.
     */
    private fun handleBuildFileModification(fullPath: Path, filenameStr: String, projectDir: Path) {
        if (!fullPath.exists()) {
            return
        }

        logger.info("{} build file changed: {}", logLabel, filenameStr)
        scheduleDebouncedCallback(projectDir, filenameStr)
    }

    /**
     * Schedules a debounced callback to handle build file changes.
     *
     * Cancels any pending callback and schedules a new one after the configured delay.
     * This prevents excessive callbacks when files are rapidly modified.
     *
     * @param projectDir The project directory path to pass to the callback.
     * @param filenameStr The filename string for error logging.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun scheduleDebouncedCallback(projectDir: Path, filenameStr: String) {
        debounceJob?.cancel()
        debounceJob = coroutineScope.launch(dispatcher) {
            if (debounceDelayMs > 0) {
                delay(debounceDelayMs)
            }
            try {
                onBuildFileChanged(projectDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("{} build file change handling failed for {}", logLabel, filenameStr, e)
            }
        }
    }

    override fun isWatching(): Boolean = watchJob?.isActive == true
}
