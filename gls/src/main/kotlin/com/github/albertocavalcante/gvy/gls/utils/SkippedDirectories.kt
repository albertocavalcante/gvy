package com.github.albertocavalcante.gvy.gls.utils

import java.nio.file.Path

/**
 * Directories that never hold project sources, at any depth: version control metadata, tool caches and vendored
 * JavaScript. The source scan, the rename guard's walk and watched-file events all skip them, so the incremental
 * source list and a rebuild scan agree.
 */
internal val ALWAYS_SKIPPED_DIRECTORIES: Set<String> =
    setOf(".git", ".hg", ".svn", ".gradle", ".idea", "node_modules")

/**
 * Whether [path], or a directory between [root] and it, is always skipped. Only the part below [root] counts: a
 * workspace checked out under `~/node_modules/` or `/.gradle/` is still scanned. Both paths are compared as given.
 */
internal fun isUnderSkippedDirectory(path: Path, root: Path): Boolean = path != root &&
    path.startsWith(root) &&
    root.relativize(path).any { it.toString() in ALWAYS_SKIPPED_DIRECTORIES }

/** Whether [path] lies under one of [roots] and not in a skipped directory below the deepest one holding it. */
internal fun isScannedUnder(path: Path, roots: Collection<Path>): Boolean {
    val owner = roots.filter { path.startsWith(it) }.maxByOrNull { it.nameCount } ?: return false
    return !isUnderSkippedDirectory(path, owner)
}
