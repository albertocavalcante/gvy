// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import java.nio.file.Files
import java.nio.file.Path

/** A nested workspace is more specific than its parent; unrelated files have no workspace. */
fun workspaceForFile(file: Path, folders: List<Path>): Path? {
    val normalized = executionPath(file)
    return folders.map(::executionPath)
        .filter { normalized.startsWith(it) }
        .maxByOrNull { it.nameCount }
}

/** Roots are only a reason to ask Gradle, never evidence authorizing a project launch. */
fun needsExecutionModel(file: Path, sourceRoots: List<Path>, knownSources: Set<Path>, requested: Boolean): Boolean {
    if (requested) return true
    val normalized = executionPath(file)
    return normalized in knownSources || sourceRoots.any { normalized.startsWith(executionPath(it)) }
}

/** A conventional source path triggers observation, but never establishes ownership. */
fun isConventionalGroovyCandidate(file: Path, root: Path): Boolean {
    val workspace = executionPath(root)
    var current = executionPath(file).parent
    while (current != null && current.startsWith(workspace)) {
        val sourceSet = current.parent
        val src = sourceSet?.parent
        val isGroovyDirectory = current.fileName?.toString() == "groovy" && Files.isDirectory(current)
        val isSourceRoot = src != null && src.fileName?.toString() == "src" && src.startsWith(workspace)
        if (isGroovyDirectory && isSourceRoot) {
            return true
        }
        current = current.parent
    }
    return false
}

/** Nested settings mark a separate build whose ownership the outer model cannot establish. */
fun nestedBuildBoundary(file: Path, workspaceRoot: Path): Path? {
    val root = executionPath(workspaceRoot)
    var directory = executionPath(file).parent
    while (directory != null && directory != root && directory.startsWith(root)) {
        if (Files.isRegularFile(directory.resolve("settings.gradle")) ||
            Files.isRegularFile(directory.resolve("settings.gradle.kts"))
        ) {
            return directory
        }
        directory = directory.parent
    }
    return null
}

/** A previously owned source needs an explicit choice if a refreshed model drops it. */
fun previousOwnerMissing(file: Path, knownSources: Set<Path>, currentSources: Set<Path>): Boolean {
    val normalized = executionPath(file)
    return normalized in knownSources && normalized !in currentSources
}

/** The project JDK is independent of the launched program's environment. */
fun projectJavaHome(options: ExecutionOptions): Result<Path?> = runCatching {
    val value = options.projectJavaHome ?: return@runCatching null
    val home = Path.of(value)
    require(home.isAbsolute) { "Project Java home must be an absolute path" }
    val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
    require(Files.isRegularFile(home.resolve("bin").resolve(executable))) { "Project Java home has no Java executable" }
    home.normalize()
}

/** Resolve existing symlinks so workspace membership cannot escape through a link. */
fun executionPath(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    return try {
        normalized.toRealPath()
    } catch (_: java.io.IOException) {
        normalized
    } catch (_: SecurityException) {
        normalized
    }
}
