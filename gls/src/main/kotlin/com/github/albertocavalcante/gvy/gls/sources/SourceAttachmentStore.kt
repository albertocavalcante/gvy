// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

private const val MAX_BINARY_IDENTITY_BYTES = 512L * 1024 * 1024
private const val MAX_ATTACHMENT_DECLARATION_BYTES = 16 * 1024
private const val MAX_ATTACHMENT_STORE_BYTES = 16L * 1024 * 1024

/** Explicit local attachment declarations. The observed SHA identifies a selection, not publisher authenticity. */
class SourceAttachmentStore(
    private val root: Path = defaultSourceAttachmentRoot(),
    private val bundledSources: BundledSourceCatalog = BundledSourceCatalog(),
    private val maximumStoreBytes: Long = MAX_ATTACHMENT_STORE_BYTES,
) {
    init {
        require(maximumStoreBytes >= MAX_ATTACHMENT_DECLARATION_BYTES)
    }

    private val directory by lazy { sourceStoreRoot(root) }

    suspend fun revision(origin: SourceOrigin, context: SourceContextIdentity): String =
        runInterruptible(Dispatchers.IO) {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return@runInterruptible "none"
            val file = declarationPath(origin, context)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@runInterruptible "none"
            Files.newInputStream(file).use { copyAndDigest(it, MAX_ATTACHMENT_DECLARATION_BYTES.toLong()) }
        }

    suspend fun record(
        symbol: ResolvedExternalSymbol,
        context: SourceLookupContext,
        sourcePath: Path,
        archive: VerifiedSourceArchive,
        expectedSha256: String?,
    ): Boolean = runInterruptible(Dispatchers.IO) {
        val binaryDigest = binaryIdentity(symbol.origin) ?: return@runInterruptible false
        if (
            symbol.origin is SourceOrigin.Archive && symbol.origin.binarySha256 != null &&
            !symbol.origin.binarySha256.equals(binaryDigest, ignoreCase = true)
        ) {
            throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
        }
        if (!context.isCurrent()) return@runInterruptible false
        val properties = Properties().apply {
            setProperty("format", "1")
            setProperty("context", context.identity.id)
            setProperty("origin", originReference(symbol.origin))
            setProperty("binarySha256", binaryDigest)
            setProperty("sourcePath", sourcePath.toAbsolutePath().normalize().toString())
            setProperty("snapshotPath", archive.path.toString())
            setProperty("observedSourceSha256", archive.sha256)
            setProperty("provenance", "explicit-local-attachment")
            expectedSha256?.let { setProperty("expectedSha256", it.lowercase()) }
        }
        val serialized = serializeDeclaration(properties)
        withSourceDiskAdmission(directory, maximumStoreBytes, MAX_ATTACHMENT_DECLARATION_BYTES.toLong()) {
            val destination = declarationPath(symbol.origin, context.identity)
            val temporary = Files.createTempFile(directory, ".attachment-", ".properties")
            try {
                Files.write(temporary, serialized)
                if (!context.isCurrent()) return@withSourceDiskAdmission false
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                true
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    suspend fun find(symbol: ResolvedExternalSymbol, context: SourceLookupContext): SourceArchiveInput? =
        runInterruptible(Dispatchers.IO) {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return@runInterruptible null
            val path = declarationPath(symbol.origin, context.identity)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@runInterruptible null
            val properties = readDeclaration(path)
            if (
                properties.getProperty("format") != "1" ||
                properties.getProperty("context") != context.identity.id ||
                properties.getProperty("origin") != originReference(symbol.origin)
            ) {
                return@runInterruptible null
            }
            val expectedBinary = properties.getProperty("binarySha256") ?: return@runInterruptible null
            if (
                !isSha256(expectedBinary) ||
                binaryIdentity(symbol.origin) != expectedBinary ||
                (symbol.origin as? SourceOrigin.Archive)?.binarySha256?.let {
                    !it.equals(expectedBinary, ignoreCase = true)
                } == true
            ) {
                throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
            }
            val sourceDigest = properties.getProperty("observedSourceSha256") ?: return@runInterruptible null
            if (!isSha256(sourceDigest)) throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
            val snapshotPath = Path.of(properties.getProperty("snapshotPath") ?: return@runInterruptible null)
            SourceArchiveInput.Verified(snapshotPath, sourceDigest)
        }

    private fun declarationPath(origin: SourceOrigin, context: SourceContextIdentity): Path =
        directory.resolve(sourceDigest(context.id + "\n" + originReference(origin)) + ".properties")

    private fun binaryIdentity(origin: SourceOrigin): String? = when (origin) {
        is SourceOrigin.Archive -> {
            if (!Files.isRegularFile(origin.binaryJar)) throw SourceStoreException(SourceFailure.IO_FAILURE)
            Files.newInputStream(origin.binaryJar).use { copyAndDigest(it, MAX_BINARY_IDENTITY_BYTES) }
        }
        is SourceOrigin.Bundled -> bundledSources.component()?.takeIf { it.id == origin.componentId }?.binaryDigest
        is SourceOrigin.Jdk -> {
            val modules = origin.imageHome.resolve("lib/modules")
            if (Files.isRegularFile(modules)) {
                Files.newInputStream(modules).use { copyAndDigest(it, MAX_BINARY_IDENTITY_BYTES) }
            } else {
                null
            }
        }
        is SourceOrigin.Unknown -> null
    }

    private fun serializeDeclaration(properties: Properties): ByteArray {
        val output = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                if (count >= MAX_ATTACHMENT_DECLARATION_BYTES) {
                    throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
                }
                super.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > MAX_ATTACHMENT_DECLARATION_BYTES - count) {
                    throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
                }
                super.write(bytes, offset, length)
            }
        }
        OutputStreamWriter(output, Charsets.UTF_8).use { properties.store(it, "Explicit source attachment") }
        return output.toByteArray()
    }

    private fun readDeclaration(path: Path): Properties = try {
        Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(MAX_ATTACHMENT_DECLARATION_BYTES + 1)
            if (bytes.size > MAX_ATTACHMENT_DECLARATION_BYTES) throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
            Properties().apply { bytes.inputStream().reader(Charsets.UTF_8).use(::load) }
        }
    } catch (invalid: IllegalArgumentException) {
        throw SourceStoreException(SourceFailure.IO_FAILURE).also { it.addSuppressed(invalid) }
    }
}

internal fun originReference(origin: SourceOrigin): String = when (origin) {
    is SourceOrigin.Archive -> "archive:" + origin.binaryJar.toAbsolutePath().normalize().toUri()
    is SourceOrigin.Bundled -> "bundled:" + origin.componentId + ":" + origin.binaryContainer
    is SourceOrigin.Jdk -> "jdk:" + origin.imageHome.toAbsolutePath().normalize().toUri()
    is SourceOrigin.Unknown -> "unknown:" + origin.resource
}

internal fun defaultSourceAttachmentRoot(): Path =
    Path.of(System.getProperty("user.home"), ".gls", "sources", "attachments-v1")
