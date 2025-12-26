package com.github.albertocavalcante.groovylsp.buildtool.maven

import com.github.albertocavalcante.groovylsp.buildtool.BuildToolFileWatcher
import com.github.albertocavalcante.groovylsp.buildtool.DEFAULT_BUILD_FILE_CHANGE_DELAY_MS
import com.github.albertocavalcante.groovylsp.buildtool.DefaultBuildFileWatcher
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Watches Maven pom.xml files for changes and triggers dependency re-resolution.
 */
class MavenBuildFileWatcher(
    coroutineScope: CoroutineScope,
    onBuildFileChanged: (Path) -> Unit,
    debounceDelayMs: Long = DEFAULT_BUILD_FILE_CHANGE_DELAY_MS,
) : BuildToolFileWatcher by DefaultBuildFileWatcher(
    logLabel = "Maven",
    coroutineScope = coroutineScope,
    onBuildFileChanged = onBuildFileChanged,
    buildFileNames = MavenBuildFiles.fileNames,
    debounceDelayMs = debounceDelayMs,
)
