package com.github.albertocavalcante.groovylsp.buildtool

import kotlinx.coroutines.CancellationException
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
 */
internal class DefaultBuildFileWatcher(
    private val logLabel: String,
    private val coroutineScope: CoroutineScope,
    private val onBuildFileChanged: (Path) -> Unit,
    private val buildFileNames: Set<String>,
    private val debounceDelayMs: Long = DEFAULT_BUILD_FILE_CHANGE_DELAY_MS,
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

    @Suppress("TooGenericExceptionCaught")
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
            // Already closed, ignore
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
            }
            logger.debug("Watch service closed, terminating watch loop.")
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

        if (!buildFileNames.contains(filenameStr)) {
            return
        }

        val fullPath = projectDir.resolve(filename)

        when (kind) {
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            -> {
                if (fullPath.exists()) {
                    logger.info("{} build file changed: {}", logLabel, filenameStr)

                    debounceJob?.cancel()
                    debounceJob = coroutineScope.launch(Dispatchers.IO) {
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
            }

            StandardWatchEventKinds.ENTRY_DELETE -> {
                logger.info("{} build file deleted: {}", logLabel, filenameStr)
            }
        }
    }

    override fun isWatching(): Boolean = watchJob?.isActive == true
}
