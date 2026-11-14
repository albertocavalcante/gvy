// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.utils

import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import com.github.albertocavalcante.gvy.gls.sources.SourceManagedPaths
import com.github.albertocavalcante.gvy.gls.sources.defaultManagedSourceRoots
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Known managed namespaces, shared by standalone workspace consumers without excluding unrelated caches. */
fun defaultWorkspaceSourceExclusion(): (Path) -> Boolean {
    val managed = SourceManagedPaths(defaultManagedSourceRoots() + listOf(ExternalSourceRegistry.defaultRoot()))
    return { path ->
        val absolute = path.toAbsolutePath()
        absolute.any { it.toString() == ".." } || managed.contains(absolute.normalize().toUri())
    }
}

/** Resolve aliases at root/document admission or a file-link boundary; ordinary walked files use the pure predicate. */
internal fun excludedSourceAlias(path: Path, excluded: (Path) -> Boolean): Boolean =
    classifySourcePath(path, excluded) != SourcePathAdmission.ORDINARY

internal fun excludedSourceFile(path: Path, excluded: (Path) -> Boolean): Boolean =
    excluded(path) || Files.isSymbolicLink(path) && excludedSourceAlias(path, excluded)

/** Rebase walked descendants onto one real root so case and ancestor aliases do not evade owned namespaces. */
internal fun sourceExclusionForRoot(root: Path, excluded: (Path) -> Boolean): (Path) -> Boolean {
    val raw = root.toAbsolutePath()
    if (raw.any { it.toString() == ".." }) return { true }
    val absolute = raw.normalize()
    val physical = try {
        absolute.toRealPath()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } ?: return excluded
    return if (absolute == physical) {
        excluded
    } else {
        { path ->
            val normalized = path.toAbsolutePath().normalize()
            excluded(normalized) ||
                normalized.startsWith(absolute) && excluded(physical.resolve(absolute.relativize(normalized)))
        }
    }
}
