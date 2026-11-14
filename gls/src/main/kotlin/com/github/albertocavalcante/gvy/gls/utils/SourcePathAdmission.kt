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
        var existing = absolute
        val missing = ArrayDeque<Path>()
        var resolved: Path? = null
        while (resolved == null) {
            try {
                resolved = existing.toRealPath()
            } catch (_: NoSuchFileException) {
                if (Files.isSymbolicLink(existing)) return SourcePathAdmission.UNAVAILABLE
                missing.addFirst(existing.fileName ?: return SourcePathAdmission.UNAVAILABLE)
                existing = existing.parent ?: return SourcePathAdmission.UNAVAILABLE
            }
        }
        var full = checkNotNull(resolved)
        missing.forEach { full = full.resolve(it) }
        if (excluded(full)) SourcePathAdmission.MANAGED else SourcePathAdmission.ORDINARY
    } catch (_: IOException) {
        SourcePathAdmission.UNAVAILABLE
    } catch (_: SecurityException) {
        SourcePathAdmission.UNAVAILABLE
    }
}
