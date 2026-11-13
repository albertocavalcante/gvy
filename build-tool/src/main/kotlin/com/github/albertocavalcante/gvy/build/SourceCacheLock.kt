// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock

private class SourceCacheCoordination {
    val lock = ReentrantLock()
    var users = 0
}

private val sourceCacheMonitor = Any()
private val sourceCacheCoordination = mutableMapOf<Path, SourceCacheCoordination>()

/**
 * Serializes one managed root's admission and publication across threads and server processes.
 * Waiting is interruptible; only this root's lock is held over bounded I/O. The zero-byte lock file
 * remains in place so waiting processes always lock the same inode after a writer exits or crashes.
 */
fun <T> withSourceCacheLock(root: Path, operation: (Path) -> T): T {
    if (Files.isSymbolicLink(root)) throw IOException("Source cache root is a link")
    Files.createDirectories(root)
    val canonical = root.toRealPath()
    val coordination = synchronized(sourceCacheMonitor) {
        sourceCacheCoordination.getOrPut(canonical, ::SourceCacheCoordination).also { coordination ->
            coordination.users++
        }
    }
    try {
        coordination.lock.lockInterruptibly()
        try {
            val lockPath = canonical.resolve(".source-admission.lock")
            if (
                Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw IOException("Source cache lock is not a regular file")
            }
            return FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                channel.lock().use { operation(canonical) }
            }
        } finally {
            coordination.lock.unlock()
        }
    } finally {
        synchronized(sourceCacheMonitor) {
            if (--coordination.users == 0) sourceCacheCoordination.remove(canonical)
        }
    }
}
