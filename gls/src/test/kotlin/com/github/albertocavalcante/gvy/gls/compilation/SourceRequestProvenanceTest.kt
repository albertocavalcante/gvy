// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.sources.SourceDocumentDescriptor
import com.github.albertocavalcante.gvy.gls.sources.SourceOrigin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRequestProvenanceTest {
    @TempDir
    lateinit var root: Path

    private val uri = URI.create("file:///source-context/Money.groovy")
    private val content = "class Money { String currency }"

    @Test
    fun `same-path classpath replacement cannot be relabelled as the old parse's source context`(): Unit = runBlocking {
        val archive = root.resolve("dependency.jar")
        writeArchive(archive, "original")
        val workspace = WorkspaceManager().apply { updateDependencies(listOf(archive)) }
        val compilation = GroovyCompilationService(workspaceManager = workspace)
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val original = assertNotNull(compilation.captureSourceRequest(uri, "java.lang.String"))
            val view = descriptor(original.symbol.origin, original.context.identity)

            writeArchive(archive, "replacement-with-different-length")

            assertFalse(original.context.isCurrent())
            assertNull(compilation.captureSourceRequest(uri, "java.lang.String"))
            assertNull(compilation.captureSourceLookupSession(view))

            compilation.clearCaches()
            assertTrue(compilation.compile(uri, content).isSuccess)
            val replacement = assertNotNull(compilation.captureSourceRequest(uri, "java.lang.String"))
            assertNotEquals(original.context.identity, replacement.context.identity)
            assertNull(
                compilation.captureSourceLookupSession(view),
                "Old source views must not acquire a new artifact version",
            )
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `final request validation detects same-stamp selected archive replacement`(): Unit = runBlocking {
        val archive = root.resolve("dependency.jar")
        writeDependencyArchive(archive, byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        val workspace = WorkspaceManager().apply { updateDependencies(listOf(archive)) }
        val compilation = GroovyCompilationService(workspaceManager = workspace)
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val request = assertNotNull(compilation.captureSourceRequest(uri, "sample.Dependency"))
            val origin = assertIs<SourceOrigin.Archive>(request.symbol.origin)
            assertEquals(archive.toAbsolutePath().normalize(), origin.binaryJar.toAbsolutePath().normalize())
            assertTrue(request.context.isCurrent())

            val originalStamp = Files.getLastModifiedTime(archive)
            val originalSize = Files.size(archive)
            writeDependencyArchive(archive, byteArrayOf(0, 0, 0, 0))
            Files.setLastModifiedTime(archive, originalStamp)

            assertEquals(originalSize, Files.size(archive))
            assertEquals(originalStamp, Files.getLastModifiedTime(archive))
            assertTrue(request.context.isCurrent(), "The cheap admission guard uses the same filesystem stamp")
            assertFalse(
                request.context.validateCurrent(),
                "Final publication validation must rehash selected archive bytes",
            )
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `a final browser validation observes a class directory change without rewalking for each candidate`(): Unit =
        runBlocking {
            val classes = Files.createDirectories(root.resolve("classes"))
            Files.write(classes.resolve("Before.class"), byteArrayOf(1))
            val workspace = WorkspaceManager().apply { updateDependencies(listOf(classes)) }
            val compilation = GroovyCompilationService(workspaceManager = workspace)
            try {
                assertTrue(compilation.compile(uri, content).isSuccess)
                val request = assertNotNull(compilation.captureSourceRequest(uri, "java.lang.String"))
                val held = assertNotNull(
                    compilation.captureSourceLookupSession(descriptor(request.symbol.origin, request.context.identity)),
                )
                assertTrue(held.isCurrent())

                Files.write(classes.resolve("Appeared.class"), byteArrayOf(2, 3))

                assertTrue(held.isCurrent(), "The per-candidate guard deliberately performs no directory tree walk")
                assertFalse(
                    held.validateCurrent(),
                    "Final emission must validate every classpath entry, including earlier absences",
                )
                assertFalse(request.context.isCurrent(), "Ordinary definition actions retain full pre/post validation")
                assertNull(
                    compilation.captureSourceRequest(uri, "java.lang.String"),
                    "An old parse cannot capture the changed directory",
                )
            } finally {
                compilation.shutdown()
            }
        }

    private fun descriptor(
        origin: SourceOrigin,
        context: com.github.albertocavalcante.gvy.gls.sources.SourceContextIdentity,
    ) = SourceDocumentDescriptor(
        "a".repeat(64),
        "b".repeat(64),
        "java/lang/String.java",
        root.resolve("content.java"),
        origin,
        context,
        "JDK java.base",
        "java",
    )

    private fun writeArchive(path: Path, content: String) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("version.txt"))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun writeDependencyArchive(path: Path, classBytes: ByteArray) {
        val crc = CRC32().apply { update(classBytes) }
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val entry = ZipEntry("sample/Dependency.class").apply {
                method = ZipEntry.STORED
                size = classBytes.size.toLong()
                compressedSize = classBytes.size.toLong()
                this.crc = crc.value
                time = 1_700_000_000_000L
            }
            zip.putNextEntry(entry)
            zip.write(classBytes)
            zip.closeEntry()
        }
    }
}
