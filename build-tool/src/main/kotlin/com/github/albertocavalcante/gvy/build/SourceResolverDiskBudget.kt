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

/** The cross-process root lock covers admission through the bounded transfer and publication. */
internal fun <T> withSourceResolverCapacity(
    root: Path,
    maximumBytes: Long,
    reservationBytes: Long,
    reservationPaths: Int,
    operation: (Path) -> T,
): T {
    require(maximumBytes > 0 && reservationBytes > 0 && reservationPaths > 0)
    if (reservationBytes > maximumBytes || reservationPaths > MAX_RESOLVER_CACHE_PATHS) throw SourceResolverCacheLimit()
    return withSourceCacheLock(root) { canonical ->
        var bytes = reservationBytes
        var paths = reservationPaths
        try {
            Files.walk(canonical).use { files ->
                val iterator = files.iterator()
                while (iterator.hasNext()) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedIOException("Source cache admission interrupted")
                    }
                    val path = iterator.next()
                    if (++paths > MAX_RESOLVER_CACHE_PATHS) throw SourceResolverCacheLimit()
                    if (Files.isSymbolicLink(path)) throw IOException("Verified source cache contains a link")
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        val size = Files.size(path)
                        if (size > maximumBytes - bytes) throw SourceResolverCacheLimit()
                        bytes += size
                    } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw IOException("Verified source cache contains an unsupported file")
                    }
                    if (bytes > maximumBytes) throw SourceResolverCacheLimit()
                }
            }
        } catch (failure: UncheckedIOException) {
            throw failure.cause ?: failure
        }
        if (bytes > maximumBytes) throw SourceResolverCacheLimit()
        operation(canonical)
    }
}
