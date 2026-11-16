// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val MAX_PRESENTED_SOURCES = 128
private const val MAX_VIEW_VARIANTS = 16_384
private const val DEFAULT_MAX_VIEW_BYTES = 256L * 1024 * 1024
private const val MAX_PRESENTED_SOURCE_BYTES = 8L * 1024 * 1024

private data class PresentationKey(val identity: String, val context: String, val source: String)

private fun viewStem(key: PresentationKey, contentDigest: String): String =
    sourceDigest("${key.identity}\u0000$contentDigest")

private fun managedViewUri(uri: URI, directory: Path): URI? {
    if (!uri.scheme.equals("file", ignoreCase = true)) return null
    val path = try {
        Path.of(uri).toAbsolutePath().normalize()
    } catch (_: IllegalArgumentException) {
        return null
    }
    return path.toUri().takeIf { path.startsWith(directory) }
}

private fun copyVerifiedContent(descriptor: SourceDocumentDescriptor, temporary: Path) {
    Files.newInputStream(descriptor.contentPath).use { input ->
        Files.newOutputStream(temporary).use { output ->
            val digest = copyAndDigest(input, MAX_PRESENTED_SOURCE_BYTES, output::write)
            if (digest != descriptor.contentDigest) throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
        }
    }
}

private fun isUnmodifiedView(path: Path, digest: String): Boolean = try {
    verifyFileDigest(path, digest, MAX_PRESENTED_SOURCE_BYTES)
    true
} catch (failure: SourceStoreException) {
    if (failure.failure == SourceFailure.UNSAFE_ARCHIVE) throw failure
    false
} catch (_: IOException) {
    false
}

/**
 * Portable editors receive independent viewing copies. They are not advertised as read-only and can never mutate
 * the verified source store through a hardlink. Modified copies remain the user's files, even when a new view opens.
 */
class PortableSourcePresentation(
    private val root: Path = defaultSourceViews(),
    private val maxViewBytes: Long = DEFAULT_MAX_VIEW_BYTES,
    private val isPersistedView: (URI, SourceDocumentDescriptor) -> Boolean = { _, _ -> false },
) : SourcePresentation {
    private data class OpenView(val contentDigest: String, val changed: Boolean)

    init {
        require(maxViewBytes >= MAX_PRESENTED_SOURCE_BYTES)
    }

    private val directory by lazy { sourceStoreRoot(root) }
    private val monitor = Any()
    private val presentations = linkedMapOf<PresentationKey, Path>()
    private val openViews = mutableMapOf<URI, OpenView>()

    override fun register(descriptor: SourceDocumentDescriptor): URI = registerAndCommit(descriptor) { }

    /** Keep creation and metadata publication under the view admission lock across registry instances. */
    internal fun registerAndCommit(descriptor: SourceDocumentDescriptor, commit: (URI) -> Unit): URI {
        verifyFileDigest(descriptor.contentPath, descriptor.contentDigest, MAX_PRESENTED_SOURCE_BYTES)
        val key = PresentationKey(
            identity = sourceDigest(SourceDescriptorCodec.identity(descriptor)),
            context = descriptor.context.id,
            source = descriptor.sourceDigest,
        )
        return withSourceDiskAdmissionOrReuse(
            directory,
            maxViewBytes,
            MAX_PRESENTED_SOURCE_BYTES,
            reuse = {
                findReusableView(descriptor, key)?.also { path ->
                    commit(path.toUri())
                    synchronized(monitor) { putPresentation(key, path) }
                }?.toUri()
            },
        ) {
            val uri = createView(descriptor, key)
            var committed = false
            AutoCloseable {
                if (!committed) rollbackNewView(uri, key, descriptor.contentDigest)
            }.use {
                commit(uri)
                committed = true
                uri
            }
        }
    }

    private fun rollbackNewView(uri: URI, key: PresentationKey, digest: String) {
        val path = Path.of(uri)
        synchronized(monitor) {
            // An editor may already have opened or changed the file; never remove its work.
            if (uri in openViews || !isUnmodifiedView(path, digest)) return
            Files.deleteIfExists(path)
            Files.deleteIfExists(path.parent)
            if (presentations[key] == path) presentations.remove(key)
        }
    }

    internal fun opened(uri: URI, contentDigest: String, content: String) {
        val view = managedViewUri(uri, directory) ?: return
        synchronized(monitor) {
            openViews[view] = OpenView(contentDigest, sourceDigest(content) != contentDigest)
        }
    }

    internal fun changed(uri: URI) {
        val view = managedViewUri(uri, directory) ?: return
        synchronized(monitor) {
            openViews[view]?.let { current -> openViews[view] = current.copy(changed = true) }
        }
    }

    internal fun closed(uri: URI) {
        val view = managedViewUri(uri, directory) ?: return
        synchronized(monitor) { openViews.remove(view) }
    }

    private fun findReusableView(descriptor: SourceDocumentDescriptor, key: PresentationKey): Path? {
        val parent = viewParent(descriptor, key)
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) return null
        ensureManagedDirectory(parent)
        val stablePrefix = "view-${viewStem(key, descriptor.contentDigest)}-"
        val (stableViews, legacyViews) = collectViewCandidates(parent, stablePrefix)
        val fileName = descriptor.entryName.substringAfterLast('/')
        return findReusableCandidate(stableViews, fileName, descriptor, false)
            ?: findReusableCandidate(legacyViews, fileName, descriptor, true)
    }

    private fun collectViewCandidates(parent: Path, stablePrefix: String): Pair<List<Path>, List<Path>> {
        val stableViews = mutableListOf<Pair<Int, Path>>()
        val legacyViews = mutableListOf<Path>()
        Files.list(parent).use { entries ->
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val view = iterator.next()
                val name = view.fileName.toString()
                if (name.startsWith(stablePrefix)) {
                    val variant = name.removePrefix(stablePrefix).toIntOrNull() ?: continue
                    stableViews.add(variant to view)
                } else if (name.startsWith("view-") && !STABLE_VIEW_DIRECTORY.matches(name)) {
                    legacyViews.add(view)
                }
            }
        }
        return stableViews.sortedBy { it.first }.map { it.second } to legacyViews
    }

    private fun findReusableCandidate(
        views: List<Path>,
        fileName: String,
        descriptor: SourceDocumentDescriptor,
        requirePersisted: Boolean,
    ): Path? {
        for (view in views) {
            requireViewDirectory(view)
            val candidate = view.resolve(fileName)
            val exists = Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
            val persisted = !requirePersisted || (exists && isPersistedView(candidate.toUri(), descriptor))
            val eligibleForReuse = exists && persisted
            if (eligibleForReuse && canReuse(candidate, descriptor.contentDigest)) {
                return candidate
            }
        }
        return null
    }

    private fun createView(descriptor: SourceDocumentDescriptor, key: PresentationKey): URI {
        val parent = viewParent(descriptor, key)
        ensureManagedDirectory(parent)
        val stem = viewStem(key, descriptor.contentDigest)
        val fileName = descriptor.entryName.substringAfterLast('/')

        for (variant in 0 until MAX_VIEW_VARIANTS) {
            val view = parent.resolve("view-$stem-$variant")
            if (Files.exists(view, LinkOption.NOFOLLOW_LINKS)) {
                requireViewDirectory(view)
                continue
            }
            return writeView(descriptor, key, view, fileName)
        }
        throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
    }

    private fun writeView(
        descriptor: SourceDocumentDescriptor,
        key: PresentationKey,
        view: Path,
        fileName: String,
    ): URI {
        Files.createDirectory(view)
        val temporary = Files.createTempFile(view, ".copying-", ".source")
        var published = false
        try {
            copyVerifiedContent(descriptor, temporary)
            val destination = view.resolve(fileName)
            publishNewFile(temporary, destination)
            published = true
            synchronized(monitor) { putPresentation(key, destination) }
            return destination.toUri()
        } finally {
            Files.deleteIfExists(temporary)
            if (!published) runCatching { Files.delete(view) }
        }
    }

    private fun viewParent(descriptor: SourceDocumentDescriptor, key: PresentationKey): Path =
        directory.resolve(sourceDigest(key.context)).resolve(descriptor.sourceDigest)

    private fun requireViewDirectory(path: Path) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
        }
        ensureManagedDirectory(path)
    }

    private fun canReuse(path: Path, digest: String): Boolean {
        if (!hasSafeManagedAncestors(path)) return false
        val uri = path.toUri()
        val openAndChanged = synchronized(monitor) {
            openViews[uri]?.let { it.contentDigest != digest || it.changed } == true
        }
        return !openAndChanged && isUnmodified(path, digest)
    }

    /** Reuse paths only while every directory from the filesystem root through the view is still real. */
    private fun hasSafeManagedAncestors(path: Path): Boolean {
        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(directory)) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
        val parent = absolute.parent
        val root = absolute.root
        if (parent == null || root == null) return false
        return safeDirectoryChain(root, parent) && Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
    }

    private fun safeDirectoryChain(root: Path, parent: Path): Boolean {
        var current = root
        for (part in parent) {
            current = current.resolve(part)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return false
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
            }
        }
        return true
    }

    private fun putPresentation(key: PresentationKey, path: Path) {
        presentations.remove(key)
        presentations[key] = path
        while (presentations.size > MAX_PRESENTED_SOURCES) presentations.remove(presentations.keys.first())
    }

    private companion object {
        val STABLE_VIEW_DIRECTORY = Regex("view-[0-9a-f]{64}-\\d+")
    }
}

internal fun defaultSourceViews(): Path = Path.of(System.getProperty("user.home"), ".gls", "cache", "source-views-v1")
