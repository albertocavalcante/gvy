// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** Cheap namespace checks, with filesystem admission only at document and write boundaries. */
internal class SourceManagedPaths(initialRoots: List<Path>) {
    private val roots = ConcurrentHashMap.newKeySet<Path>()

    init {
        initialRoots.forEach(::add)
    }

    fun add(root: Path) {
        roots.add(root.toAbsolutePath().normalize())
        realPath(root)?.let(roots::add)
    }

    fun contains(uri: URI): Boolean = path(uri)?.let(::containsPath) == true

    /** Fail closed if a destination cannot be resolved safely; no file is created by this check. */
    fun destinationIsManaged(uri: URI): Boolean {
        val destination = path(uri) ?: return true
        if (containsPath(destination)) return true
        val resolved = realPath(destination) ?: return true
        return containsPath(resolved) || aliasesManagedAncestor(resolved)
    }

    /** Resource operations also protect directories containing a managed root, including aliases. */
    fun resourceOperationIsManaged(uri: URI): Boolean {
        val destination = path(uri) ?: return true
        if (touchesManagedRoot(destination)) return true
        val resolved = realPath(destination) ?: return true
        if (touchesManagedRoot(resolved) || aliasesManagedAncestor(resolved)) return true
        for (root in roots) {
            var ancestor: Path? = root.parent
            while (ancestor != null) {
                try {
                    if (Files.isSameFile(resolved, ancestor)) return true
                } catch (_: NoSuchFileException) {
                    // Managed namespaces can be reserved before the underlying directory exists.
                } catch (_: IOException) {
                    return true
                } catch (_: SecurityException) {
                    return true
                }
                ancestor = ancestor.parent
            }
        }
        return false
    }

    private fun touchesManagedRoot(path: Path): Boolean = containsPath(path) || roots.any { it.startsWith(path) }

    private fun containsPath(path: Path): Boolean = roots.any(path::startsWith)

    private fun aliasesManagedAncestor(path: Path): Boolean {
        var ancestor: Path? = path
        while (ancestor != null) {
            for (root in roots) {
                try {
                    if (Files.isSameFile(ancestor, root)) return true
                } catch (_: NoSuchFileException) {
                    // New descendants and not-yet-created cache roots have no identity to compare.
                } catch (_: IOException) {
                    return true
                } catch (_: SecurityException) {
                    return true
                }
            }
            ancestor = ancestor.parent
        }
        return false
    }

    private fun path(uri: URI): Path? = if (uri.scheme.equals("file", ignoreCase = true)) {
        try {
            Path.of(uri).toAbsolutePath().normalize()
        } catch (_: IllegalArgumentException) {
            null
        }
    } else {
        null
    }

    private fun realPath(path: Path): Path? {
        return try {
            var ancestor = path.toAbsolutePath().normalize()
            val missing = ArrayDeque<Path>()
            while (true) {
                try {
                    Files.readAttributes(ancestor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    break
                } catch (_: NoSuchFileException) {
                    missing.addFirst(ancestor.fileName ?: return null)
                    ancestor = ancestor.parent ?: return null
                }
            }
            var resolved = ancestor.toRealPath()
            missing.forEach { resolved = resolved.resolve(it) }
            resolved.normalize()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
