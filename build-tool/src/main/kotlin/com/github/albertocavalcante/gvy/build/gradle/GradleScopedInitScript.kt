package com.github.albertocavalcante.gvy.build.gradle

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest

/**
 * Installs the packaged scoped-dependency init script at a stable, content-addressed path.
 *
 * Gradle caches compiled init scripts by path and content, so a stable path avoids a new script
 * cache entry per request. The script is executable build logic. On POSIX file systems the
 * directory must be a real directory owned by the process's user and not writable by group or
 * others; parent directories are not checked. The file is rewritten unless its bytes equal the
 * packaged resource. Any failure to establish that is an [IOException]; callers treat it as
 * unavailable evidence.
 */
internal object GradleScopedInitScript {
    private const val RESOURCE = "/gvy/gradle/scoped-dependencies.init.gradle"
    private val ownerOnly = PosixFilePermissions.fromString("rwx------")
    private val sharedWrite = setOf(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)

    val content: ByteArray by lazy {
        val stream = GradleScopedInitScript::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing packaged resource $RESOURCE")
        stream.use { it.readAllBytes() }
    }

    /**
     * The per-user directory, or null when no absolute user home is known. There is deliberately no
     * shared temporary-directory fallback: another local user could pre-create it.
     */
    fun defaultDirectory(): Path? {
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
        val base = runCatching { Paths.get(home) }.getOrNull()?.takeIf { it.isAbsolute } ?: return null
        return base.resolve(".groovy-lsp").resolve("init-scripts")
    }

    fun install(directory: Path): Path {
        val bytes = content
        val target = directory.resolve("scoped-dependencies-${sha256(bytes)}.init.gradle")
        ensureTrustedDirectory(directory)
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.readAllBytes(target).contentEquals(bytes)) {
            return target
        }
        val staging = Files.createTempFile(directory, "scoped-dependencies-", ".tmp")
        try {
            Files.write(staging, bytes)
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
        return target
    }

    private fun ensureTrustedDirectory(directory: Path) {
        val posix = directory.fileSystem.supportedFileAttributeViews().contains("posix")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (posix) {
                    Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(ownerOnly))
                } else {
                    Files.createDirectories(directory)
                }
            } catch (_: FileAlreadyExistsException) {
                // Created concurrently; verified below.
            }
        }
        trust(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "is not a real directory: $directory" }
        if (!posix) return
        val attributes = Files
            .getFileAttributeView(directory, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            .readAttributes()
        trust(attributes.owner() == processOwner(directory)) { "is not owned by the current user: $directory" }
        trust(attributes.permissions().none { it in sharedWrite }) { "is writable by other users: $directory" }
    }

    /**
     * The owner of a file this process just created. Unlike a `user.name` lookup this also works for
     * arbitrary container UIDs without a passwd entry; a directory we cannot write to fails here.
     */
    private fun processOwner(directory: Path): UserPrincipal {
        val probe = Files.createTempFile(directory, "owner-", ".probe")
        return try {
            Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS)
        } finally {
            Files.deleteIfExists(probe)
        }
    }

    private inline fun trust(condition: Boolean, problem: () -> String) {
        if (!condition) throw IOException("Init script directory ${problem()}")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
