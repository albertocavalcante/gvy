package com.github.albertocavalcante.groovylsp.indexing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PathUtilsTest {

    @Test
    fun `toCanonicalUri returns file URI without trailing slash for non-root paths`(@TempDir tempDir: File) {
        val file = File(tempDir, "hello.txt").apply { writeText("hi") }
        val uri = PathUtils.toCanonicalUri(file.absolutePath)

        assertTrue(uri.startsWith("file:///"))
        assertFalse(uri.endsWith("/"))
    }

    @Test
    fun `toCanonicalUri removes trailing slash added for directories`(@TempDir tempDir: File) {
        val uri = PathUtils.toCanonicalUri(tempDir.absolutePath)

        assertTrue(uri.startsWith("file:///"))
        assertFalse(uri.endsWith("/"))
    }

    @Test
    fun `toCanonicalUri preserves trailing slash for root`() {
        assertEquals("file:///", PathUtils.toCanonicalUri("/"))
    }
}
