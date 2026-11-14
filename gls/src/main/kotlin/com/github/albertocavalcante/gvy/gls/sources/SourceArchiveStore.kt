// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipFile

private const val MIB = 1024L * 1024L
private const val DEFAULT_ARCHIVE_MIB = 64L
private const val DEFAULT_ENTRY_MIB = 8L
private const val DEFAULT_ARCHIVE_ENTRY_COUNT = 20_000
private const val DEFAULT_INDEX_CHARACTERS = 2_097_152
private const val DEFAULT_ENTRY_PATH_CHARACTERS = 1_024
private const val DEFAULT_CENTRAL_DIRECTORY_MIB = 8L
private const val DEFAULT_ARCHIVE_STORE_MIB = 512L
private const val DEFAULT_CONTENT_STORE_MIB = 256L

/** Limits apply before publication, including archives selected locally by the user. */
data class SourceStoreLimits(
    val maxArchiveBytes: Long = DEFAULT_ARCHIVE_MIB * MIB,
    val maxEntryBytes: Long = DEFAULT_ENTRY_MIB * MIB,
    val maxArchiveEntries: Int = DEFAULT_ARCHIVE_ENTRY_COUNT,
    val maxIndexCharacters: Int = DEFAULT_INDEX_CHARACTERS,
    val maxEntryPathCharacters: Int = DEFAULT_ENTRY_PATH_CHARACTERS,
    val maxCentralDirectoryBytes: Long = DEFAULT_CENTRAL_DIRECTORY_MIB * MIB,
    val maxArchiveStoreBytes: Long = DEFAULT_ARCHIVE_STORE_MIB * MIB,
    val maxContentStoreBytes: Long = DEFAULT_CONTENT_STORE_MIB * MIB,
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
    ZipDirectoryValidator.validate(archive.path, limits)
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
            val name = SourceArchiveNameValidator.validate(entry.name, entry.isDirectory, limits)
            val folded = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            if (!normalizedNames.add(folded)) throw SourceStoreException(SourceFailure.UNSAFE_ARCHIVE)
            if (!entry.isDirectory && (name.endsWith(".java") || name.endsWith(".groovy"))) entries[name] = name
        }
    }
    verifyFileDigest(archive.path, archive.sha256, limits.maxArchiveBytes)
    return SourceArchiveIndex(entries)
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

private const val SHA256_HEX_LENGTH = 64

internal fun isSha256(value: String): Boolean = value.length == SHA256_HEX_LENGTH &&
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
