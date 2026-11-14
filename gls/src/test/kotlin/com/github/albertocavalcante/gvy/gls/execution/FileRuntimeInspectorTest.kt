// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileRuntimeInspectorTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `oversized compressed core manifest is rejected before parsing`() {
        val manifest = manifestWithPadding(1_000)
        val jar = archive("core.jar", manifest, core = true)
        assertTrue(Files.size(jar) < manifest.size)
        assertRejected(listOf(jar), "manifest")
    }

    @Test
    fun `runtime jar count is bounded before inspecting contents`() {
        val jar = archive("core.jar", manifestWithPadding(0), core = true)
        assertRejected(List(1_025) { jar }, "many")
    }

    @Test
    fun `individual and total runtime file sizes are bounded`() {
        val tooLarge = sparseFile("too-large.jar", 128L * 1024 * 1024 + 1)
        assertRejected(listOf(tooLarge), "size")
        val underLimit = sparseFile("under-limit.jar", 90L * 1024 * 1024)
        assertRejected(List(3) { underLimit }, "size")
    }

    @Test
    fun `large legitimate manifest and many auxiliary jars remain usable`() {
        val manifest = manifestWithPadding(600)
        assertTrue(manifest.size < 64 * 1024)
        val core = archive("core.jar", manifest, core = true)
        val auxiliary = archive("aux.jar", null, core = false)
        val result = FileRuntimeInspector.inspect(listOf(core.toString()) + List(128) { auxiliary.toString() })
        assertTrue(result is ExecutionStep.Value)
        if (result is ExecutionStep.Value) assertEquals("4.0.33", result.value.version)
    }

    @Test
    fun `Groovy modules must match the selected core version`() {
        val core = archive("core.jar", manifestWithPadding(0), core = true)
        val mismatch = archive("json.jar", null, core = false, releaseInfo = "ImplementationVersion=2.4.21\n")
        assertRejected(listOf(mismatch, core), "version")
        val matching = archive("matching.jar", null, core = false, releaseInfo = "ImplementationVersion=4.0.33\n")
        assertTrue(FileRuntimeInspector.inspect(listOf(core.toString(), matching.toString())) is ExecutionStep.Value)
    }

    @Test
    fun `unrelated auxiliary versions do not conflict with Groovy`() {
        val core = archive("core.jar", manifestWithPadding(0), core = true)
        val manifest = "Manifest-Version: 1.0\r\nImplementation-Version: 2.4.21\r\n\r\n".toByteArray()
        val unrelated = archive("aux.jar", manifest, core = false)
        assertTrue(FileRuntimeInspector.inspect(listOf(core.toString(), unrelated.toString())) is ExecutionStep.Value)
    }

    @Test
    fun `compressed Groovy release metadata is bounded`() {
        val core = archive("core.jar", manifestWithPadding(0), core = true)
        val module = archive("module.jar", null, core = false, releaseInfo = "#" + "x".repeat(65_536))
        assertRejected(listOf(core, module), "metadata")
    }

    @Test
    fun `unsafe manifest version is omitted from the runtime label`() {
        val manifest = "Manifest-Version: 1.0\r\nImplementation-Version: ${"x".repeat(65)}\r\n\r\n"
        val core = archive("core.jar", manifest.toByteArray(), core = true)
        val result = FileRuntimeInspector.inspect(listOf(core.toString()))
        assertTrue(result is ExecutionStep.Value)
        if (result is ExecutionStep.Value) assertEquals(null, result.value.version)
    }

    private fun assertRejected(paths: List<Path>, reason: String) {
        val result = FileRuntimeInspector.inspect(paths.map(Path::toString))
        assertTrue(result is ExecutionStep.Stop)
        if (result is ExecutionStep.Stop) assertTrue(result.response.reason!!.contains(reason, ignoreCase = true))
    }

    private fun archive(name: String, manifest: ByteArray?, core: Boolean, releaseInfo: String? = null): Path {
        val path = directory.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { jar ->
            if (manifest != null) {
                jar.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                jar.write(manifest)
                jar.closeEntry()
            }
            if (releaseInfo != null) {
                jar.putNextEntry(ZipEntry("META-INF/groovy-release-info.properties"))
                jar.write(releaseInfo.toByteArray())
                jar.closeEntry()
            }
            jar.putNextEntry(ZipEntry(if (core) "groovy/lang/GroovySystem.class" else "example/Other.class"))
            jar.closeEntry()
        }
        return path
    }

    private fun manifestWithPadding(lines: Int): ByteArray = buildString {
        append("Manifest-Version: 1.0\r\nImplementation-Version: 4.0.33\r\n")
        repeat(lines) { index -> append("X-Pad-$index: ${"a".repeat(64)}\r\n") }
        append("\r\n")
    }.toByteArray(Charsets.UTF_8)

    private fun sparseFile(name: String, size: Long): Path {
        val path = directory.resolve(name)
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(size) }
        return path
    }
}
