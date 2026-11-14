// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.gls.utils.SourcePathAdmission
import com.github.albertocavalcante.gvy.gls.utils.classifySourcePath
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Durable source-view metadata; active documents never retain compiler or classloader resources. */
class ExternalSourceRegistry(
    private val root: Path = defaultRoot(),
    private val portablePresentation: SourcePresentation = PortableSourcePresentation(root.resolve("views")),
    private val virtualDocumentsEnabled: () -> Boolean = { false },
    managedRoots: List<Path> = defaultManagedSourceRoots(),
    private val sourceAdmission: (Path, (Path) -> Boolean) -> Boolean = { path, excluded ->
        classifySourcePath(path, excluded) != SourcePathAdmission.ORDINARY
    },
    private val maximumMetadataBytes: Long = DEFAULT_METADATA_BYTES,
) : SourcePresentation {
    init {
        require(maximumMetadataBytes >= MAX_METADATA_BYTES) { "Metadata budget must admit one bounded descriptor" }
    }

    private val managedPaths = SourceManagedPaths(managedRoots + listOf(root))
    private val metadataRoot by lazy {
        sourceStoreRoot(root).also(managedPaths::add).resolve("descriptors")
            .also(::ensureManagedDirectory)
    }
    private val descriptors = LinkedHashMap<URI, SourceDocumentDescriptor>(16, 0.75f, true)
    private val changed = ConcurrentHashMap.newKeySet<URI>()

    override fun register(descriptor: SourceDocumentDescriptor): URI {
        checkNotNull(readVerified(descriptor.contentPath, descriptor.contentDigest)) {
            "Source content failed verification"
        }
        val key = sha256(SourceDescriptorCodec.identity(descriptor).toByteArray(StandardCharsets.UTF_8))
        val uri = if (virtualDocumentsEnabled()) {
            URI(SCHEME, key, "/" + descriptor.sourceDigest + "/" + descriptor.entryName, null)
        } else {
            portablePresentation.register(descriptor)
        }
        require(uri.scheme == SCHEME || uri.scheme == "file") { "Unsupported source presentation" }
        filePath(uri)?.let { view ->
            require(managedPaths.contains(uri)) { "A source viewing copy must use a managed namespace" }
            require(!Files.isSameFile(view, descriptor.contentPath)) {
                "A viewing copy must not expose immutable content"
            }
        }

        persist(uri, descriptor)
        cache(uri, descriptor)
        return uri
    }

    /** Managed paths remain browse-only even after metadata eviction, corruption or an editor restart. */
    fun isManaged(uri: URI): Boolean = uri.scheme.equals(SCHEME, ignoreCase = true) ||
        managedPaths.contains(uri) ||
        synchronized(descriptors) { descriptors.containsKey(uri) }

    /** Includes immutable stores and filesystem aliases when validating an explicit Copy destination. */
    fun isManagedDestination(uri: URI): Boolean = isManaged(uri) || managedPaths.destinationIsManaged(uri)

    /** File and directory operations may not replace a managed namespace or any containing ancestor. */
    fun shouldGuardResourceOperation(uri: URI): Boolean = isManaged(uri) || managedPaths.resourceOperationIsManaged(uri)

    fun descriptor(uri: URI): SourceDocumentDescriptor? {
        synchronized(descriptors) { descriptors[uri] }?.let { return it }
        if (!isManaged(uri)) return null
        val loaded = load(uri) ?: return null
        cache(uri, loaded)
        return loaded
    }

    /** Returns original verified content, never a modified portable viewing copy. */
    fun content(uri: URI): String? {
        val descriptor = descriptor(uri) ?: return null
        if (uri in changed) return null
        val path = filePath(uri)
        if (path != null && readVerified(path, descriptor.contentDigest) == null) return null
        val bytes = readVerified(descriptor.contentPath, descriptor.contentDigest) ?: return null
        return decodeUtf8(bytes)
    }

    fun opened(uri: URI, text: String) {
        val descriptor = descriptor(uri) ?: return
        if (sha256(text.toByteArray(StandardCharsets.UTF_8)) != descriptor.contentDigest) changed.add(uri)
    }

    fun changed(uri: URI) {
        if (descriptor(uri) != null) changed.add(uri)
    }

    fun closed(uri: URI) {
        synchronized(descriptors) { descriptors.remove(uri) }
        changed.remove(uri)
    }

    /** Resolve one document admission without changing the pure managed namespace predicate. */
    fun shouldGuardDocument(uri: URI): Boolean {
        if (isManaged(uri)) return true
        if (!uri.scheme.equals("file", ignoreCase = true)) return false
        val path = filePath(uri) ?: return true
        return sourceAdmission(path) { managedPaths.contains(it.toUri()) }
    }

    private fun cache(uri: URI, descriptor: SourceDocumentDescriptor) {
        synchronized(descriptors) {
            descriptors[uri] = descriptor
            while (descriptors.size > MAX_CACHED_DESCRIPTORS) descriptors.remove(descriptors.keys.first())
        }
    }

    private fun persist(uri: URI, descriptor: SourceDocumentDescriptor) {
        if (load(uri) == descriptor) return
        withSourceDiskAdmission(metadataRoot, maximumMetadataBytes, MAX_METADATA_BYTES) {
            if (load(uri) != descriptor) writeDescriptor(uri, descriptor)
        }
    }

    private fun writeDescriptor(uri: URI, descriptor: SourceDocumentDescriptor) {
        val bytes = SourceDescriptorCodec.serialize(uri, descriptor)
        val temporary = Files.createTempFile(metadataRoot, "source-", ".partial")
        try {
            Files.write(temporary, bytes)
            Files.move(
                temporary,
                metadataPath(uri),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun load(uri: URI): SourceDocumentDescriptor? {
        return try {
            val path = metadataPath(uri)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_METADATA_BYTES) {
                return null
            }

            val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                input.readNBytes(MAX_METADATA_BYTES.toInt() + 1)
            }
            if (bytes.size > MAX_METADATA_BYTES) return null

            val text = decodeUtf8(bytes) ?: return null
            val properties = Properties().apply {
                StringReader(text).use { reader -> load(reader) }
            }
            SourceDescriptorCodec.decode(uri, properties)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun metadataPath(uri: URI): Path =
        metadataRoot.resolve(sha256(uri.toString().toByteArray(StandardCharsets.UTF_8)) + ".properties")

    companion object {
        const val SCHEME = "gvy-source"
        const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 64L * 1024
        private const val DEFAULT_METADATA_BYTES = 16L * 1024 * 1024
        private const val MAX_CACHED_DESCRIPTORS = 1024

        fun defaultRoot(): Path = Path.of(System.getProperty("user.home"), ".gls", "cache", "source-browser-v1")

        private fun filePath(uri: URI): Path? = if (uri.scheme.equals("file", ignoreCase = true)) {
            try {
                Path.of(uri).toAbsolutePath()
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }

        private fun decodeUtf8(bytes: ByteArray): String? = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }

        private fun readVerified(path: Path, expected: String): ByteArray? = try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_SOURCE_BYTES) {
                null
            } else {
                Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val bytes = input.readNBytes(MAX_SOURCE_BYTES + 1)
                    bytes.takeIf { it.size <= MAX_SOURCE_BYTES && sha256(it) == expected }
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
