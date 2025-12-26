package com.github.albertocavalcante.groovylsp.buildtool.gradle

import com.github.albertocavalcante.groovylsp.buildtool.BuildToolFileWatcher
import com.github.albertocavalcante.groovylsp.buildtool.DEFAULT_BUILD_FILE_CHANGE_DELAY_MS
import com.github.albertocavalcante.groovylsp.buildtool.DefaultBuildFileWatcher
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Watches Gradle related build files for changes and triggers dependency re-resolution.
 * Monitors build.gradle, build.gradle.kts, settings.gradle, and settings.gradle.kts files.
 */
class GradleBuildFileWatcher(
    coroutineScope: CoroutineScope,
    onBuildFileChanged: (Path) -> Unit,
    debounceDelayMs: Long = DEFAULT_BUILD_FILE_CHANGE_DELAY_MS,
) : BuildToolFileWatcher by DefaultBuildFileWatcher(
    logLabel = "Gradle",
    coroutineScope = coroutineScope,
    onBuildFileChanged = onBuildFileChanged,
    buildFileNames = GradleBuildFiles.fileNames,
    debounceDelayMs = debounceDelayMs,
)
