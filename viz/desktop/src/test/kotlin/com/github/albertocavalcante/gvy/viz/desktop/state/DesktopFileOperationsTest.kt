// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopFileOperationsTest {
    @Test
    fun `existing destination is unchanged including its mode`() = runBlocking {
        val directory = Files.createTempDirectory("gvy-export-test-")
        try {
            assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"))
            val target = directory.resolve("existing.json")
            Files.writeString(target, "before")
            val mode = PosixFilePermissions.fromString("rwxr-xr--")
            Files.setPosixFilePermissions(target, mode)
            val failure = assertFailsWith<IOException> { LocalDesktopFileOperations.write(target.toFile(), "after") }
            assertTrue(failure.message?.contains("choose a new filename") == true)
            assertEquals("before", Files.readString(target))
            assertEquals(mode, Files.getPosixFilePermissions(target))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `symlink destinations are rejected without changing link or target`() = runBlocking {
        val directory = Files.createTempDirectory("gvy-export-test-")
        try {
            val target = directory.resolve("target.json")
            val link = directory.resolve("link.json")
            Files.writeString(target, "before")
            try {
                Files.createSymbolicLink(link, target.fileName)
            } catch (_: UnsupportedOperationException) {
                assumeTrue(false, "Symbolic links unavailable")
            }
            assertFailsWith<IOException> { LocalDesktopFileOperations.write(link.toFile(), "after") }
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("before", Files.readString(target))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `read only destinations are not overwritten`() = runBlocking {
        val directory = Files.createTempDirectory("gvy-export-test-")
        try {
            assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"))
            val target = directory.resolve("readonly.json")
            Files.writeString(target, "before")
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"))
            assumeTrue(!Files.isWritable(target), "Current user can write read-only files")
            assertFailsWith<IOException> { LocalDesktopFileOperations.write(target.toFile(), "after") }
            assertEquals("before", Files.readString(target))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `new destination is created with complete contents`() = runBlocking {
        val directory = Files.createTempDirectory("gvy-export-test-")
        try {
            val target = directory.resolve("new.json")
            LocalDesktopFileOperations.write(target.toFile(), "content")
            assertEquals("content", Files.readString(target))
            Files.list(directory).use { assertEquals(1L, it.count()) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `concurrent creation does not replace the winner and removes temporary file`() {
        val directory = Files.createTempDirectory("gvy-export-test-")
        try {
            val target = directory.resolve("race.json")
            assertFailsWith<IOException> {
                writeNewFile(target, "ours") { destination, temporary ->
                    Files.writeString(destination, "theirs")
                    Files.createLink(destination, temporary)
                }
            }
            assertEquals("theirs", Files.readString(target))
            Files.list(directory).use { assertEquals(1L, it.count()) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unsupported safe publication fails and removes temporary file`() {
        val directory = Files.createTempDirectory("gvy-export-test-")
        try {
            val target = directory.resolve("new.json")
            val failure = assertFailsWith<IOException> {
                writeNewFile(target, "content") { _, _ -> throw UnsupportedOperationException() }
            }
            assertTrue(failure.message?.contains("choose another location") == true)
            assertTrue(!Files.exists(target))
            Files.list(directory).use { assertEquals(0L, it.count()) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `link failure tells user to choose a different name or location`() {
        val directory = Files.createTempDirectory("gvy-export-test-")
        try {
            val target = directory.resolve("new.json")
            val failure = assertFailsWith<IOException> {
                writeNewFile(target, "content") { _, _ -> throw IOException("link unavailable") }
            }
            assertTrue(failure.message?.contains("another filename or location") == true)
            Files.list(directory).use { assertEquals(0L, it.count()) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
