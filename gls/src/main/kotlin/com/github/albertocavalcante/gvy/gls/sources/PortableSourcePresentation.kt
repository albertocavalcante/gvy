// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_PRESENTED_SOURCES = 128
private const val DEFAULT_MAX_VIEW_BYTES = 256L * 1024 * 1024
private const val MAX_PRESENTED_SOURCE_BYTES = 8L * 1024 * 1024

/**
 * Portable editors receive independent viewing copies. They are not advertised as read-only and can never mutate
 * the verified source store through a hardlink. Modified copies remain the user's files, even when a new view opens.
 */
class PortableSourcePresentation(
    private val root: Path = defaultSourceViews(),
    private val maxViewBytes: Long = DEFAULT_MAX_VIEW_BYTES,
) : SourcePresentation {
    init {
        require(maxViewBytes >= MAX_PRESENTED_SOURCE_BYTES)
    }

    private val directory by lazy { sourceStoreRoot(root) }
    private val monitor = Any()
    private val presentations = linkedMapOf<PresentationKey, Path>()

    override fun register(descriptor: SourceDocumentDescriptor): URI {
        verifyFileDigest(descriptor.contentPath, descriptor.contentDigest, MAX_PRESENTED_SOURCE_BYTES)
        val key = PresentationKey(descriptor.context.id, descriptor.sourceDigest, descriptor.entryName)
        val previous = synchronized(monitor) { presentations[key] }
        if (previous != null && isUnmodified(previous, descriptor.contentDigest)) return previous.toUri()

        return withSourceDiskAdmission(directory, maxViewBytes, MAX_PRESENTED_SOURCE_BYTES) {
            createView(descriptor, key)
        }
    }

    private fun createView(descriptor: SourceDocumentDescriptor, key: PresentationKey): URI {
        val parent = directory.resolve(sourceDigest(descriptor.context.id)).resolve(descriptor.sourceDigest)
        ensureManagedDirectory(parent)
        // Reserve a unique directory atomically; publication never replaces any existing viewing file.
        val view = Files.createTempDirectory(parent, "view-")
        val temporary = Files.createTempFile(view, ".copying-", ".source")
        try {
            copyVerifiedContent(descriptor, temporary)
            val destination = view.resolve(descriptor.entryName.substringAfterLast('/'))
            publishNewFile(temporary, destination)
            synchronized(monitor) { putPresentation(key, destination) }
            return destination.toUri()
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun copyVerifiedContent(descriptor: SourceDocumentDescriptor, temporary: Path) {
        Files.newInputStream(descriptor.contentPath).use { input ->
            Files.newOutputStream(temporary).use { output ->
                val digest = copyAndDigest(input, MAX_PRESENTED_SOURCE_BYTES, output::write)
                if (digest != descriptor.contentDigest) throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
            }
        }
    }

    private fun isUnmodified(path: Path, digest: String): Boolean = try {
        verifyFileDigest(path, digest, MAX_PRESENTED_SOURCE_BYTES)
        true
    } catch (_: IOException) {
        false
    }

    private fun putPresentation(key: PresentationKey, path: Path) {
        presentations.remove(key)
        presentations[key] = path
        while (presentations.size > MAX_PRESENTED_SOURCES) presentations.remove(presentations.keys.first())
    }

    private data class PresentationKey(val context: String, val source: String, val entry: String)
}

internal fun defaultSourceViews(): Path = Path.of(System.getProperty("user.home"), ".gls", "cache", "source-views-v1")
