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
        return realPath(destination)?.let(::resolvedResourceOperationIsManaged) ?: true
    }

    private fun touchesManagedRoot(path: Path): Boolean = containsPath(path) || roots.any { it.startsWith(path) }

    private fun containsPath(path: Path): Boolean = roots.any(path::startsWith)

    private fun aliasesManagedAncestor(path: Path): Boolean = pathAncestors(path).any { ancestor ->
        roots.any { root -> sameFileOrFailClosed(ancestor, root) }
    }

    private fun resolvedResourceOperationIsManaged(path: Path): Boolean = touchesManagedRoot(path) ||
        aliasesManagedAncestor(path) ||
        roots.asSequence()
            .flatMap { root -> pathAncestors(root.parent) }
            .any { ancestor -> sameFileOrFailClosed(path, ancestor) }

    private fun pathAncestors(path: Path?): Sequence<Path> = generateSequence(path) { current -> current.parent }

    private fun sameFileOrFailClosed(first: Path, second: Path): Boolean = try {
        Files.isSameFile(first, second)
    } catch (_: NoSuchFileException) {
        // New descendants and reserved roots can lack a filesystem identity.
        false
    } catch (_: IOException) {
        true
    } catch (_: SecurityException) {
        true
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
