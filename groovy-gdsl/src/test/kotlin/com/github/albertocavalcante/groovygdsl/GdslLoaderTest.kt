package com.github.albertocavalcante.groovygdsl

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for GDSL file loading and parsing.
 */
class GdslLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should load GDSL file from path`() {
        val gdslContent = """
            contributor(context()) {
                method name: 'node', type: void, params: [body: Closure]
                method name: 'stage', type: void, params: [name: String, body: Closure]
            }
        """.trimIndent()

        val gdslFile = tempDir.resolve("pipeline.gdsl")
        Files.writeString(gdslFile, gdslContent)

        val loader = GdslLoader()
        val result = loader.loadGdslFile(gdslFile.toString())

        assertNotNull(result)
        assertTrue(result.isSuccess)
        assertEquals(gdslContent, result.content)
    }

    @Test
    fun `should handle missing GDSL file gracefully`() {
        val loader = GdslLoader()
        val result = loader.loadGdslFile("/nonexistent/file.gdsl")

        assertNotNull(result)
        assertTrue(result.isFailure)
        assertNotNull(result.error)
    }

    @Test
    fun `should load multiple GDSL files`() {
        val gdsl1 = tempDir.resolve("pipeline.gdsl")
        Files.writeString(gdsl1, "// Pipeline GDSL")

        val gdsl2 = tempDir.resolve("steps.gdsl")
        Files.writeString(gdsl2, "// Steps GDSL")

        val loader = GdslLoader()
        val results = loader.loadAllGdslFiles(
            listOf(
                gdsl1.toString(),
                gdsl2.toString(),
            ),
        )

        assertEquals(2, results.successful.size)
        assertEquals(0, results.failed.size)
    }

    @Test
    fun `should report failures for missing files in batch load`() {
        val gdsl1 = tempDir.resolve("existing.gdsl")
        Files.writeString(gdsl1, "// Existing")

        val loader = GdslLoader()
        val results = loader.loadAllGdslFiles(
            listOf(
                gdsl1.toString(),
                "/nonexistent/missing.gdsl",
            ),
        )

        assertEquals(1, results.successful.size)
        assertEquals(1, results.failed.size)
    }

    @Test
    fun `should handle empty GDSL paths list`() {
        val loader = GdslLoader()
        val results = loader.loadAllGdslFiles(emptyList())

        assertEquals(0, results.successful.size)
        assertEquals(0, results.failed.size)
    }
}
