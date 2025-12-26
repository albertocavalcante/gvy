package com.github.albertocavalcante.groovylsp.buildtool.gradle

import com.github.albertocavalcante.groovylsp.buildtool.BuildToolFileWatcher
import com.github.albertocavalcante.groovylsp.buildtool.DEFAULT_BUILD_FILE_CHANGE_DELAY_MS
import com.github.albertocavalcante.groovylsp.buildtool.DefaultBuildFileWatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * Watches Gradle related build files for changes and triggers dependency re-resolution.
 * Monitors build.gradle, build.gradle.kts, settings.gradle, and settings.gradle.kts files.
 *
 * @param coroutineScope The coroutine scope for launching watch operations.
 * @param onBuildFileChanged Callback invoked when a Gradle build file changes.
 * @param debounceDelayMs Delay in milliseconds before invoking the callback.
 *                        Defaults to [DEFAULT_BUILD_FILE_CHANGE_DELAY_MS].
 * @param dispatcher The coroutine dispatcher for I/O operations. Defaults to [Dispatchers.IO].
 */
class GradleBuildFileWatcher(
    coroutineScope: CoroutineScope,
    onBuildFileChanged: (Path) -> Unit,
    debounceDelayMs: Long = DEFAULT_BUILD_FILE_CHANGE_DELAY_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BuildToolFileWatcher by DefaultBuildFileWatcher(
    logLabel = "Gradle",
    coroutineScope = coroutineScope,
    onBuildFileChanged = onBuildFileChanged,
    buildFileNames = GradleBuildFiles.fileNames,
    debounceDelayMs = debounceDelayMs,
    dispatcher = dispatcher,
)
