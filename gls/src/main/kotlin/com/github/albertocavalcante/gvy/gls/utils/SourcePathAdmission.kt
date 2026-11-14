// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.utils

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal enum class SourcePathAdmission { ORDINARY, MANAGED, UNAVAILABLE }

/** Explicit admission-time I/O. A new file is classified through its nearest existing ancestor. */
internal fun classifySourcePath(path: Path, excluded: (Path) -> Boolean): SourcePathAdmission {
    val raw = path.toAbsolutePath()
    // Normalizing a parent segment can change its meaning across a symbolic link.
    if (raw.any { it.toString() == ".." }) return SourcePathAdmission.UNAVAILABLE
    val absolute = raw.normalize()
    if (excluded(absolute)) return SourcePathAdmission.MANAGED
    return try {
        val physical = resolveSourcePath(absolute)
        when {
            physical == null -> SourcePathAdmission.UNAVAILABLE
            excluded(physical) -> SourcePathAdmission.MANAGED
            else -> SourcePathAdmission.ORDINARY
        }
    } catch (_: IOException) {
        SourcePathAdmission.UNAVAILABLE
    } catch (_: SecurityException) {
        SourcePathAdmission.UNAVAILABLE
    }
}

/** Resolve missing descendants without interpreting a dangling link as an ordinary new path. */
private fun resolveSourcePath(absolute: Path): Path? {
    return try {
        var existing = absolute
        val missing = ArrayDeque<Path>()
        var resolved = realPathOrMissing(existing)
        while (resolved == null) {
            val name = existing.fileName
            val parent = existing.parent
            if (Files.isSymbolicLink(existing) || name == null || parent == null) return null
            missing.addFirst(name)
            existing = parent
            resolved = realPathOrMissing(existing)
        }
        var full = checkNotNull(resolved)
        missing.forEach { full = full.resolve(it) }
        full
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private fun realPathOrMissing(path: Path): Path? = try {
    path.toRealPath()
} catch (_: NoSuchFileException) {
    null
}
