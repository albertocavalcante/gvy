// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** A bounded metadata observation, not a content hash or a lease on immutable artifact bytes. */
data class NativeArtifactStamp(
    val size: Long,
    val lastModifiedMillis: Long,
    /** Null identifies a regular file. A directory counts every regular descendant file. */
    val files: Long? = null,
    val observedPaths: Int = 1,
    /** Filesystem object identity for regular files; null where the provider cannot supply one. */
    val fileKey: String? = null,
    /** Nanosecond-capable spelling retained in addition to the millisecond compatibility field. */
    val lastModifiedTime: String? = null,
    /** Stable creation identity for regular files on providers without a file key. */
    val creationTime: String? = null,
    /** Hash of every observed directory entry's relative path and metadata, not file contents. */
    val directoryIdentity: String? = null,
    /** Precise POSIX change time when the provider exposes it; a fast invalidation hint, not a content identity. */
    val changeTime: String? = null,
    /** Bounded content digest used only when the filesystem has no change-time attribute. */
    val contentIdentity: String? = null,
) {
    /** Legacy four-field constructor for Java callers. */
    constructor(size: Long, lastModifiedMillis: Long, files: Long?, observedPaths: Int) : this(
        size,
        lastModifiedMillis,
        files,
        observedPaths,
        null,
        null,
        null,
        null,
        null,
        null,
    )

    companion object {
        const val MAX_DIRECTORY_PATHS = 4096
        const val MAX_CONTENT_IDENTITY_BYTES = 512L * 1024 * 1024

        /**
         * Explicit filesystem I/O. Missing, unreadable, linked descendants and over-budget trees are unknown.
         * Disabling content identity is only suitable for lightweight checks against an already verified receipt:
         * metadata alone cannot detect every in-place rewrite on providers without change times.
         */
        fun observe(
            path: Path,
            maxPaths: Int = MAX_DIRECTORY_PATHS,
            checkCancelled: () -> Unit = {},
        ): NativeArtifactStamp? = observe(
            path,
            maxPaths,
            checkCancelled,
            MAX_CONTENT_IDENTITY_BYTES,
            true,
        )

        /**
         * Observes with explicit content-identity limits. The three-argument overload above retains the original
         * JVM entry point; callers that need custom digest policy must use this overload.
         */
        fun observe(
            path: Path,
            maxPaths: Int = MAX_DIRECTORY_PATHS,
            checkCancelled: () -> Unit = {},
            maxContentBytes: Long,
            includeContentIdentity: Boolean = true,
        ): NativeArtifactStamp? {
            require(maxPaths > 0)
            require(maxContentBytes >= 0)
            return try {
                checkCancelled()
                val root = path.toRealPath()
                val attributes = Files.readAttributes(root, BasicFileAttributes::class.java)
                val changeTime = changeTime(root)
                when {
                    attributes.isRegularFile -> {
                        val content = if (changeTime == null && includeContentIdentity) {
                            digestFile(root, attributes.size(), maxContentBytes, checkCancelled) ?: return null
                        } else {
                            null
                        }
                        if (content != null && fileMetadata(root) != metadata(attributes, changeTime)) return null
                        NativeArtifactStamp(
                            attributes.size(),
                            attributes.lastModifiedTime().toMillis(),
                            fileKey = attributes.fileKey()?.toString(),
                            lastModifiedTime = attributes.lastModifiedTime().toString(),
                            creationTime = attributes.creationTime().toString(),
                            changeTime = changeTime,
                            contentIdentity = content,
                        )
                    }
                    attributes.isDirectory -> DirectoryObservation(root, maxPaths, maxContentBytes, checkCancelled)
                        .observe(root, changeTime, includeContentIdentity)
                    else -> null
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }
}

private class DirectoryObservation(
    private val root: Path,
    private val maxPaths: Int,
    private val maxContentBytes: Long,
    private val checkCancelled: () -> Unit,
) :
    SimpleFileVisitor<Path>() {
    private var paths = 0
    private var files = 0L
    private var bytes = 0L
    private var newest = Long.MIN_VALUE
    private var complete = true
    private var missingChangeTime = false
    private val entries = mutableMapOf<String, List<String>>()
    private val filesToHash = mutableListOf<Path>()

    fun observe(root: Path, rootChangeTime: String?, includeContentIdentity: Boolean): NativeArtifactStamp? {
        if (rootChangeTime == null) missingChangeTime = true
        Files.walkFileTree(root, this)
        return if (complete) {
            val contentIdentity = if (missingChangeTime && includeContentIdentity) {
                digestTree(filesToHash, maxContentBytes) ?: return null
            } else {
                null
            }
            if (contentIdentity != null) {
                val after = DirectoryObservation(root, maxPaths, maxContentBytes, checkCancelled)
                    .metadataOnly(root) ?: return null
                if (after != entries) return null
            }
            NativeArtifactStamp(
                bytes,
                newest,
                files,
                paths,
                directoryIdentity = directoryIdentity(entries),
                changeTime = rootChangeTime,
                contentIdentity = contentIdentity,
            )
        } else {
            null
        }
    }

    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (!visit()) return FileVisitResult.TERMINATE
        newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
        addIdentity(dir, attrs)
        return FileVisitResult.CONTINUE
    }

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (!visit()) return FileVisitResult.TERMINATE
        if (!attrs.isRegularFile || attrs.size() > Long.MAX_VALUE - bytes) {
            complete = false
            return FileVisitResult.TERMINATE
        }
        files++
        bytes += attrs.size()
        newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
        filesToHash.add(file)
        addIdentity(file, attrs)
        return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc

    private fun visit(): Boolean {
        checkCancelled()
        if (++paths <= maxPaths) return true
        complete = false
        return false
    }

    private fun addIdentity(path: Path, attributes: BasicFileAttributes) {
        val relative = root.relativize(path).toString()
        val changeTime = changeTime(path)
        if (changeTime == null) missingChangeTime = true
        entries[relative] = metadata(attributes, changeTime)
    }

    private fun metadataOnly(root: Path): Map<String, List<String>>? {
        Files.walkFileTree(root, this)
        return entries.takeIf { complete }
    }

    private fun digestTree(paths: List<Path>, maxBytes: Long): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        var totalBytes = 0L
        for (path in paths.sortedBy { root.relativize(it).toString() }) {
            checkCancelled()
            val size = Files.size(path)
            if (size < 0 || size > maxBytes - totalBytes) return null
            val relative = root.relativize(path).toString().toByteArray(Charsets.UTF_8)
            digest.update(relative.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(relative)
            Files.newInputStream(path).use { input ->
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > maxBytes) return null
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun directoryIdentity(entries: Map<String, List<String>>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entries.toSortedMap().forEach { (path, metadata) ->
        (listOf(path) + metadata).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun metadata(attributes: BasicFileAttributes, changeTime: String?): List<String> = listOf(
    attributes.isRegularFile.toString(),
    attributes.isDirectory.toString(),
    attributes.size().toString(),
    attributes.lastModifiedTime().toString(),
    attributes.creationTime().toString(),
    attributes.fileKey()?.toString().orEmpty(),
    changeTime.orEmpty(),
)

private fun fileMetadata(path: Path): List<String> = metadata(
    Files.readAttributes(path, BasicFileAttributes::class.java), changeTime(path),
)

private const val HASH_BUFFER_BYTES = 8192

private fun changeTime(path: Path): String? = try {
    (Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS) as? FileTime)?.toString()
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: UnsupportedOperationException) {
    null
} catch (_: SecurityException) {
    null
}

private fun digestFile(path: Path, size: Long, maxBytes: Long, checkCancelled: () -> Unit): String? {
    if (size < 0 || size > maxBytes) return null
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER_BYTES)
    var totalBytes = 0L
    Files.newInputStream(path).use { input ->
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            if (totalBytes > maxBytes) return null
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
