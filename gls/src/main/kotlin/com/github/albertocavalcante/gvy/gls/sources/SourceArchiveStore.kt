// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipFile

private const val MIB = 1024L * 1024L

/** Limits apply before publication, including archives selected locally by the user. */
data class SourceStoreLimits(
    val maxArchiveBytes: Long = 64 * MIB,
    val maxEntryBytes: Long = 8 * MIB,
    val maxArchiveEntries: Int = 20_000,
    val maxIndexCharacters: Int = 2 * 1024 * 1024,
    val maxEntryPathCharacters: Int = 1024,
    val maxCentralDirectoryBytes: Long = 8 * MIB,
    val maxArchiveStoreBytes: Long = 512 * MIB,
    val maxContentStoreBytes: Long = 256 * MIB,
) {
    init {
        require(maxArchiveBytes > 0 && maxEntryBytes > 0)
        require(maxArchiveEntries > 0 && maxIndexCharacters > 0 && maxEntryPathCharacters > 0)
        require(maxCentralDirectoryBytes > 0)
        require(maxArchiveStoreBytes >= maxArchiveBytes && maxContentStoreBytes >= maxEntryBytes)
    }
}

sealed interface SourceArchiveInput {
    /** Explicit local attachment: its digest supplies identity, without claiming repository verification. */
    data class Local(val path: Path) : SourceArchiveInput
    data class Verified(val path: Path, val expectedSha256: String) : SourceArchiveInput
    class VerifiedResource internal constructor(val expectedSha256: String, internal val open: () -> InputStream) :
        SourceArchiveInput
}

@ConsistentCopyVisibility
data class VerifiedSourceArchive internal constructor(val path: Path, val sha256: String)

internal class SourceStoreException(val failure: SourceFailure) : IOException(failure.name)

/** Copies inputs into a content-addressed private store, checking expected digests before any ZIP parsing. */
class SourceArchiveStore(root: Path, private val limits: SourceStoreLimits = SourceStoreLimits()) {
    private val root = sourceStoreRoot(root)
    fun importArchive(input: SourceArchiveInput): VerifiedSourceArchive {
        val expected = input.expectedSha256()
        if (expected != null && !isSha256(expected)) {
            throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
        }

        if (expected != null) {
            val digest = expected.lowercase(Locale.ROOT)
            return withSourceDiskAdmissionOrReuse(
                root,
                limits.maxArchiveStoreBytes,
                limits.maxArchiveBytes,
                reuse = { cachedArchive(digest) },
            ) { importAdmitted(input) }
        }

        return try {
            withSourceDiskAdmission(root, limits.maxArchiveStoreBytes, limits.maxArchiveBytes) {
                importAdmitted(input)
            }
        } catch (failure: SourceStoreException) {
            if (failure.failure != SourceFailure.LIMIT_EXCEEDED || input !is SourceArchiveInput.Local) throw failure
            reuseLocalArchive(input.path, failure)
        }
    }

    private fun SourceArchiveInput.expectedSha256(): String? = when (this) {
        is SourceArchiveInput.Local -> null
        is SourceArchiveInput.Verified -> expectedSha256
        is SourceArchiveInput.VerifiedResource -> expectedSha256
    }

    private fun cachedArchive(digest: String): VerifiedSourceArchive? {
        val path = root.resolve("$digest.jar")
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        verifyFileDigest(path, digest, limits.maxArchiveBytes)
        return VerifiedSourceArchive(path, digest)
    }

    private fun reuseLocalArchive(path: Path, admissionFailure: SourceStoreException): VerifiedSourceArchive {
        val digest = openArchiveFile(path).use { copyAndDigest(it, limits.maxArchiveBytes) }
        return cachedArchive(digest) ?: throw admissionFailure
    }

    private fun importAdmitted(input: SourceArchiveInput): VerifiedSourceArchive {
        val expected = when (input) {
            is SourceArchiveInput.Local -> null
            is SourceArchiveInput.Verified -> input.expectedSha256
            is SourceArchiveInput.VerifiedResource -> input.expectedSha256
        }
        if (expected != null && !isSha256(expected)) throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
        ensureManagedDirectory(root)
        val temporary = Files.createTempFile(root, ".acquiring-", ".jar")
        try {
            val stream = when (input) {
                is SourceArchiveInput.Local -> openArchiveFile(input.path)
                is SourceArchiveInput.Verified -> openArchiveFile(input.path)
                is SourceArchiveInput.VerifiedResource -> input.open()
            }
            val digest = stream.use {
                Files.newOutputStream(temporary).use { output ->
                    copyAndDigest(it, limits.maxArchiveBytes, output::write)
                }
            }
            if (expected != null && !digest.equals(expected, ignoreCase = true)) {
                throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
            }
            val destination = root.resolve("$digest.jar")
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                verifyFileDigest(destination, digest, limits.maxArchiveBytes)
            } else {
                publishNewFile(temporary, destination)
            }
            return VerifiedSourceArchive(destination, digest)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun openArchiveFile(path: Path): InputStream {
        // Explicitly reject pipes/devices before opening: a byte limit cannot bound a blocking FIFO read.
        val resolved = path.toRealPath()
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
        }
        if (Files.size(resolved) > limits.maxArchiveBytes) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
        return Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS)
    }
}

internal class SourceArchiveIndex(entries: Map<String, String>) {
    val entries: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(entries))

    fun entryFor(binaryName: String, prefix: String = ""): String? {
        val exact = prefix + binaryName.replace('.', '/')
        val exactCandidates = listOf("$exact.java", "$exact.groovy").filter { it in entries }
        if (exactCandidates.isNotEmpty()) return exactCandidates.singleOrNull()
        val outer = prefix + binaryName.substringBefore('$').replace('.', '/')
        val candidates = listOf("$outer.java", "$outer.groovy").filter { it in entries }
        return candidates.singleOrNull()
    }
}

/** Validates the entire name table before exposing even one entry from an archive. */
internal fun indexSourceArchive(archive: VerifiedSourceArchive, limits: SourceStoreLimits): SourceArchiveIndex {
    verifyFileDigest(archive.path, archive.sha256, limits.maxArchiveBytes)
    validateZipDirectoryBounds(archive.path, limits)
    val entries = linkedMapOf<String, String>()
    val normalizedNames = hashSetOf<String>()
    var count = 0
    var characters = 0
    ZipFile(archive.path.toFile()).use { zip ->
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            checkSourceInterruption()
            val entry = enumeration.nextElement()
            count++
            characters += entry.name.length
            if (count > limits.maxArchiveEntries || characters > limits.maxIndexCharacters) {
                throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
            }
            val name = validateSourceEntryName(entry.name, entry.isDirectory, limits)
            val folded = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            if (!normalizedNames.add(folded)) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
            if (!entry.isDirectory && (name.endsWith(".java") || name.endsWith(".groovy"))) entries[name] = name
        }
    }
    verifyFileDigest(archive.path, archive.sha256, limits.maxArchiveBytes)
    return SourceArchiveIndex(entries)
}

private const val ZIP_EOCD_LENGTH = 22
private const val MAX_ZIP_COMMENT_BYTES = 65535
private const val ZIP_EOCD_SIGNATURE = 0x06054b50

/** Bound central-directory allocation before ZipFile constructs its in-memory table. */
private fun validateZipDirectoryBounds(path: Path, limits: SourceStoreLimits) {
    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        if (size < ZIP_EOCD_LENGTH) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
        val count = minOf(size, (ZIP_EOCD_LENGTH + MAX_ZIP_COMMENT_BYTES).toLong()).toInt()
        val tail = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN)
        var position = size - count
        while (tail.hasRemaining()) {
            val read = channel.read(tail, position)
            if (read < 0) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
            position += read
        }
        for (at in count - ZIP_EOCD_LENGTH downTo 0) {
            if (tail.getInt(at) != ZIP_EOCD_SIGNATURE) continue
            val comment = tail.getShort(at + 20).toInt() and 0xffff
            if (at + ZIP_EOCD_LENGTH + comment != count) continue
            val disk = tail.getShort(at + 4).toInt() and 0xffff
            val centralDisk = tail.getShort(at + 6).toInt() and 0xffff
            val diskEntries = tail.getShort(at + 8).toInt() and 0xffff
            val entries = tail.getShort(at + 10).toInt() and 0xffff
            val centralBytes = tail.getInt(at + 12).toLong() and 0xffffffffL
            val centralOffset = tail.getInt(at + 16).toLong() and 0xffffffffL
            if (disk != 0 || centralDisk != 0 || diskEntries != entries) {
                throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
            }
            if (entries > limits.maxArchiveEntries || centralBytes > limits.maxCentralDirectoryBytes) {
                throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
            }
            if (centralOffset + centralBytes > size - count + at) {
                throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
            }
            return
        }
        throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
    }
}

private val WINDOWS_DEVICE_NAME = Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")

private fun validateSourceEntryName(name: String, directory: Boolean, limits: SourceStoreLimits): String {
    if (name.length > limits.maxEntryPathCharacters) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
    val value = if (directory) name.removeSuffix("/") else name
    val segments = value.split('/')
    if (
        value.isEmpty() ||
        value.startsWith('/') ||
        '\\' in value ||
        ':' in value ||
        '\u0000' in value ||
        segments.any { segment ->
            segment.isEmpty() ||
                segment == "." ||
                segment == ".." ||
                segment.endsWith('.') ||
                segment.endsWith(' ') ||
                WINDOWS_DEVICE_NAME.matches(segment)
        }
    ) {
        throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
    }
    return value
}

internal fun ensureManagedDirectory(directory: Path) {
    val absolute = directory.toAbsolutePath().normalize()
    var current = absolute.root
    for (part in absolute) {
        current = current.resolve(part)
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
            }
        } else {
            Files.createDirectory(current)
        }
    }
}

/** Resolve the explicitly configured root once; archive entries never choose a filesystem root. */
internal fun sourceStoreRoot(root: Path): Path {
    if (Files.isSymbolicLink(root)) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
    Files.createDirectories(root)
    return root.toRealPath()
}

internal fun publishNewFile(temporary: Path, destination: Path) {
    try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        // A non-atomic fallback could expose partially written source to another request.
        throw SourceStoreException(SourceFailure.IO_FAILURE).also { it.addSuppressed(e) }
    }
}

internal fun isSha256(value: String): Boolean = value.length == 64 &&
    value.all { character ->
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
    }

internal fun verifyFileDigest(path: Path, expected: String, maxBytes: Long) {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
    val actual = Files.newInputStream(path).use { copyAndDigest(it, maxBytes) }
    if (!actual.equals(expected, ignoreCase = true)) throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
}

internal fun copyAndDigest(
    input: InputStream,
    maxBytes: Long,
    consume: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        checkSourceInterruption()
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
        digest.update(buffer, 0, read)
        consume(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun checkSourceInterruption() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source operation interrupted")
}
