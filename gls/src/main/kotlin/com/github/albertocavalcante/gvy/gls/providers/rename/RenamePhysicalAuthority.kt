// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Resolves links before processing missing suffixes, including paths containing `..`. */
private fun physicalPath(path: Path): Path? {
    val absolute = path.toAbsolutePath()
    return try {
        absolute.toRealPath()
    } catch (_: NoSuchFileException) {
        // A dangling link is not an unsaved file. Nor may an unreadable existing entry be guessed.
        try {
            Files.readAttributes(absolute, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            null
        } catch (_: NoSuchFileException) {
            val parent = absolute.parent ?: return null
            val name = absolute.fileName ?: return null
            physicalPath(parent)?.resolve(name)?.normalize()
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/** Captured before parsing; every physical path used to authorize edits is checked again at delivery. */
internal class RenamePhysicalAuthority(rootPaths: List<Path>, primaryUri: URI) {
    private val roots = rootPaths.map { it to physicalPath(it) }
    private val primary = runCatching { Path.of(primaryUri) }.getOrNull()
    private val observations = linkedMapOf<Path, Path?>()

    init {
        primary?.let {
            observe(it)
            it.parent?.let(::observe)
        }
    }

    private fun observe(path: Path): Path? = observations.getOrPut(path) { physicalPath(path) }

    fun contains(uri: URI, rootlessAllowed: Boolean): Boolean {
        val path = runCatching { Path.of(uri) }.getOrNull() ?: return false
        val target = observe(path) ?: return false
        return if (roots.isEmpty()) rootlessAllowed else roots.any { (_, root) -> root != null && target.startsWith(root) }
    }

    fun current(): Boolean = primary != null && roots.all { (path, key) -> key != null && physicalPath(path) == key } &&
        observations.all { (path, key) -> key != null && physicalPath(path) == key }
}
