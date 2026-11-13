// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A bounded metadata observation, not a content hash or a lease on immutable artifact bytes. */
data class NativeArtifactStamp(
    val size: Long,
    val lastModifiedMillis: Long,
    /** Null identifies a regular file. A directory counts every regular descendant file. */
    val files: Long? = null,
    val observedPaths: Int = 1,
) {
    companion object {
        const val MAX_DIRECTORY_PATHS = 4096

        /** Explicit filesystem I/O. Missing, unreadable, linked descendants and over-budget trees are unknown. */
        fun observe(
            path: Path,
            maxPaths: Int = MAX_DIRECTORY_PATHS,
            checkCancelled: () -> Unit = {},
        ): NativeArtifactStamp? {
            require(maxPaths > 0)
            return try {
                checkCancelled()
                val root = path.toRealPath()
                val attributes = Files.readAttributes(root, BasicFileAttributes::class.java)
                when {
                    attributes.isRegularFile -> NativeArtifactStamp(
                        attributes.size(),
                        attributes.lastModifiedTime().toMillis(),
                    )
                    attributes.isDirectory -> DirectoryObservation(maxPaths, checkCancelled).observe(root)
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

private class DirectoryObservation(private val maxPaths: Int, private val checkCancelled: () -> Unit) :
    SimpleFileVisitor<Path>() {
    private var paths = 0
    private var files = 0L
    private var bytes = 0L
    private var newest = Long.MIN_VALUE
    private var complete = true

    fun observe(root: Path): NativeArtifactStamp? {
        Files.walkFileTree(root, this)
        return if (complete) NativeArtifactStamp(bytes, newest, files, paths) else null
    }

    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (!visit()) return FileVisitResult.TERMINATE
        newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
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
        return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc

    private fun visit(): Boolean {
        checkCancelled()
        if (++paths <= maxPaths) return true
        complete = false
        return false
    }
}
