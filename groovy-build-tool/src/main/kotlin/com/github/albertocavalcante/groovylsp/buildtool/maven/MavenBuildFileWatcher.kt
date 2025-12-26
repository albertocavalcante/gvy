package com.github.albertocavalcante.groovylsp.buildtool.maven

import com.github.albertocavalcante.groovylsp.buildtool.BuildToolFileWatcher
import com.github.albertocavalcante.groovylsp.buildtool.DEFAULT_BUILD_FILE_CHANGE_DELAY_MS
import com.github.albertocavalcante.groovylsp.buildtool.DefaultBuildFileWatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * Watches Maven pom.xml files for changes and triggers dependency re-resolution.
 *
 * @param coroutineScope The coroutine scope for launching watch operations.
 * @param onBuildFileChanged Callback invoked when a Maven build file changes.
 * @param debounceDelayMs Delay in milliseconds before invoking the callback.
 *                        Defaults to [DEFAULT_BUILD_FILE_CHANGE_DELAY_MS].
 * @param dispatcher The coroutine dispatcher for I/O operations. Defaults to [Dispatchers.IO].
 */
class MavenBuildFileWatcher(
    coroutineScope: CoroutineScope,
    onBuildFileChanged: (Path) -> Unit,
    debounceDelayMs: Long = DEFAULT_BUILD_FILE_CHANGE_DELAY_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BuildToolFileWatcher by DefaultBuildFileWatcher(
    logLabel = "Maven",
    coroutineScope = coroutineScope,
    onBuildFileChanged = onBuildFileChanged,
    buildFileNames = MavenBuildFiles.fileNames,
    debounceDelayMs = debounceDelayMs,
    dispatcher = dispatcher,
)
