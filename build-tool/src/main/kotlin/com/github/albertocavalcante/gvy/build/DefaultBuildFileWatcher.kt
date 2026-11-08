package com.github.albertocavalcante.gvy.build

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var session: WatchSession? = null

    private class WatchSession(
        val service: WatchService,
        val scope: CoroutineScope,
        val changes: BuildFileChangeDebouncer,
        val keys: ConcurrentHashMap<WatchKey, Path>,
    ) {
        @Volatile
        var job: Job? = null
    }

    private sealed class PollResult {
        object Timeout : PollResult()
        object Interrupted : PollResult()
        data class Success(val watchKey: WatchKey) : PollResult()
    }

    @Synchronized
    override fun startWatching(projectDir: Path) {
        if (session?.job?.isActive == true) {
            logger.debug { "$logLabel build file watcher already active for project" }
            return
        }

        stopWatching()
        if (!coroutineScope.isActive) return
        try {
            val created = createSession(projectDir)
            session = created
            val job = created.scope.launch(Dispatchers.IO) { watchLoop(created) }
            created.job = job
            job.invokeOnCompletion { closeSession(created) }
            logger.info { "Started watching $logLabel build files in: $projectDir" }
        } catch (e: IOException) {
            logger.error(e) { "IO error starting $logLabel build file watcher" }
        } catch (e: UnsupportedOperationException) {
            logger.error(e) { "File watching not supported on this platform" }
        }
    }

    @Synchronized
    override fun stopWatching() {
        val stopping = session ?: return
        session = null
        closeSession(stopping)
        logger.info { "Stopped $logLabel build file watcher" }
    }

    private fun createSession(projectDir: Path): WatchSession {
        val service = FileSystems.getDefault().newWatchService()
        var handedOff = false
        try {
            val key = projectDir.register(
                service,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            val parentContext = coroutineScope.coroutineContext
            val scope = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]))
            val changes = BuildFileChangeDebouncer(scope, dispatcher, debounceDelayMs, ::notifyBuildFileChanged)
            return WatchSession(service, scope, changes, ConcurrentHashMap(mapOf(key to projectDir)))
                .also { handedOff = true }
        } finally {
            if (!handedOff) closeService(service)
        }
    }

    private fun closeSession(stopping: WatchSession) {
        // The parent owns callbacks even before launch() returns their Job to the coordinator.
        stopping.scope.cancel()
        stopping.keys.keys.forEach { it.cancel() }
        stopping.keys.clear()
        closeService(stopping.service)
    }

    private fun closeService(service: WatchService) {
        try {
            service.close()
        } catch (e: IOException) {
            logger.warn(e) { "IO error closing $logLabel build file watcher" }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
    private fun watchLoop(session: WatchSession) {
        try {
            while (session.scope.isActive) {
                val result = pollWatchKey(session.service)
                val shouldContinue = handlePollResult(result, session)
                if (!shouldContinue) {
                    break
                }
            }
        } catch (e: ClosedWatchServiceException) {
            if (session.scope.isActive) {
                logger.warn(e) { "Watch service closed unexpectedly" }
                throw e
            } else {
                logger.debug { "Watch service closed, terminating watch loop." }
            }
        } catch (e: IOException) {
            logger.error(e) { "IO error in $logLabel build file watch loop" }
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error in $logLabel build file watch loop" }
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
        logger.debug { "Watch service interrupted" }
        PollResult.Interrupted
    }

    private fun handleUnknownWatchKey(watchKey: WatchKey) {
        logger.warn { "Unknown watch key, skipping events" }
        watchKey.reset()
    }

    private fun handlePollResult(result: PollResult, session: WatchSession): Boolean = when (result) {
        PollResult.Interrupted -> false
        PollResult.Timeout -> true
        is PollResult.Success -> handleWatchKey(result.watchKey, session)
    }

    private fun handleWatchKey(watchKey: WatchKey, session: WatchSession): Boolean {
        val projectDir = session.keys[watchKey]
        if (projectDir == null) {
            handleUnknownWatchKey(watchKey)
            return true
        }
        return processWatchKeyEvents(watchKey, projectDir, session)
    }

    private fun processWatchKeyEvents(watchKey: WatchKey, projectDir: Path, session: WatchSession): Boolean {
        watchKey.pollEvents().forEach { event ->
            processWatchEvent(event, projectDir, session)
        }

        val stillValid = watchKey.reset()
        if (!stillValid) {
            logger.warn { "Watch key no longer valid for: $projectDir" }
            session.keys.remove(watchKey)
        }

        return stillValid
    }

    @Suppress("TooGenericExceptionCaught")
    private fun processWatchEvent(event: WatchEvent<*>, projectDir: Path, session: WatchSession) {
        val kind = event.kind()

        if (kind == StandardWatchEventKinds.OVERFLOW) {
            logger.warn { "File system watch overflow - some events may have been lost" }
            return
        }

        val filename = event.context() as? Path ?: return
        val filenameStr = filename.name

        if (!shouldHandleBuildFile(filenameStr)) {
            return
        }

        val fullPath = projectDir.resolve(filename)
        handleBuildFileEvent(kind, fullPath, filenameStr, projectDir, session)
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
    private fun handleBuildFileEvent(
        kind: WatchEvent.Kind<*>,
        fullPath: Path,
        filenameStr: String,
        projectDir: Path,
        session: WatchSession,
    ) {
        when (kind) {
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            -> handleBuildFileModification(fullPath, filenameStr, projectDir, session)

            StandardWatchEventKinds.ENTRY_DELETE -> {
                logger.info { "$logLabel build file deleted: $filenameStr" }
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
    private fun handleBuildFileModification(
        fullPath: Path,
        filenameStr: String,
        projectDir: Path,
        session: WatchSession,
    ) {
        if (!fullPath.exists()) {
            return
        }

        logger.info { "$logLabel build file changed: $filenameStr" }
        session.changes.submit(projectDir, filenameStr)
    }

    /**
     * Runs the consumer callback at the IO boundary while preserving cancellation.
     *
     * @param projectDir The project directory path to pass to the callback.
     * @param filenameStr The filename string for error logging.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun notifyBuildFileChanged(projectDir: Path, filenameStr: String) {
        try {
            onBuildFileChanged(projectDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "$logLabel build file change handling failed for $filenameStr" }
        }
    }

    override fun isWatching(): Boolean = session?.job?.isActive == true
}
