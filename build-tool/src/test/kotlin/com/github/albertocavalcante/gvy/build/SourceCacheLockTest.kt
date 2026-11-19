// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceCacheLockTest {
    @TempDir lateinit var root: Path

    @Test
    fun `publication holds an operating system file lock and retains its inode afterward`() {
        val lockPath = root.resolve(".source-admission.lock")
        withSourceCacheLock(root) {
            FileChannel.open(lockPath, StandardOpenOption.WRITE).use { competing ->
                assertFailsWith<OverlappingFileLockException> { competing.tryLock() }
            }
        }
        assertTrue(Files.isRegularFile(lockPath))
        assertEquals(0L, Files.size(lockPath))
        FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
            channel.tryLock().use { lock -> assertTrue(lock.isValid) }
        }
    }

    @Test
    fun `another root can publish while a bounded transfer holds the first roots lock`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val held = worker.submit {
                withSourceCacheLock(root.resolve("one")) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("independent", withSourceCacheLock(root.resolve("two")) { "independent" })
            release.countDown()
            held.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
