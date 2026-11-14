// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import java.io.IOException
import java.io.InterruptedIOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class SourceResolverCacheLimit : IOException("Verified source cache limit exceeded")

private const val MAX_RESOLVER_CACHE_PATHS = 16_384

private data class CacheUsage(var bytes: Long, var paths: Int)

/** The cross-process root lock covers admission through the bounded transfer and publication. */
internal fun <T> withSourceResolverCapacity(
    root: Path,
    maximumBytes: Long,
    reservationBytes: Long,
    reservationPaths: Int,
    operation: (Path) -> T,
): T {
    validateReservation(maximumBytes, reservationBytes, reservationPaths)
    return withSourceCacheLock(root) { canonical ->
        measureCacheUsage(canonical, maximumBytes, reservationBytes, reservationPaths)
        operation(canonical)
    }
}

private fun validateReservation(maximumBytes: Long, reservationBytes: Long, reservationPaths: Int) {
    require(maximumBytes > 0 && reservationBytes > 0 && reservationPaths > 0)
    if (reservationBytes > maximumBytes || reservationPaths > MAX_RESOLVER_CACHE_PATHS) {
        throw SourceResolverCacheLimit()
    }
}

private fun measureCacheUsage(root: Path, maximumBytes: Long, reservationBytes: Long, reservationPaths: Int) {
    val usage = CacheUsage(reservationBytes, reservationPaths)
    try {
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                checkInterrupted()
                addCachePath(iterator.next(), maximumBytes, usage)
            }
        }
    } catch (failure: UncheckedIOException) {
        throw failure.cause ?: failure
    }
    if (usage.bytes > maximumBytes) throw SourceResolverCacheLimit()
}

private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source cache admission interrupted")
}

private fun addCachePath(path: Path, maximumBytes: Long, usage: CacheUsage) {
    countCachePath(usage)
    if (isSupportedCachePath(path)) addCacheFileSize(path, maximumBytes, usage)
}

private fun countCachePath(usage: CacheUsage) {
    if (++usage.paths > MAX_RESOLVER_CACHE_PATHS) throw SourceResolverCacheLimit()
}

private fun isSupportedCachePath(path: Path): Boolean {
    if (Files.isSymbolicLink(path)) throw IOException("Verified source cache contains a link")
    val regularFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    if (!regularFile && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Verified source cache contains an unsupported file")
    }
    return regularFile
}

private fun addCacheFileSize(path: Path, maximumBytes: Long, usage: CacheUsage) {
    val size = Files.size(path)
    if (size > maximumBytes - usage.bytes) throw SourceResolverCacheLimit()
    usage.bytes += size
}
