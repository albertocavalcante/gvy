package com.github.albertocavalcante.groovylsp.buildtool

import java.nio.file.Path

data class WorkspaceResolution(val dependencies: List<Path>, val sourceDirectories: List<Path>)
