package com.github.albertocavalcante.groovylsp.sources

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceJarExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var extractor: SourceJarExtractor

    @BeforeEach
    fun setup() {
        extractor = SourceJarExtractor(tempDir.resolve("extracted"))
    }

    @Test
    fun `extractAndIndex - extracts java files from source jar`() {
        val sourceJar = createTestSourceJar(
            "com/example/service/UserService.java" to "package com.example.service; class UserService {}",
            "com/example/model/User.java" to "package com.example.model; class User {}",
        )

        val index = extractor.extractAndIndex(sourceJar)

        assertEquals(2, index.size)
        assertTrue(index.containsKey("com.example.service.UserService"))
        assertTrue(index.containsKey("com.example.model.User"))

        // Verify files were extracted
        val userServicePath = index["com.example.service.UserService"]
        assertNotNull(userServicePath)
        assertTrue(Files.exists(userServicePath))
        assertTrue(Files.readString(userServicePath).contains("class UserService"))
    }

    @Test
    fun `extractAndIndex - caches results`() {
        val sourceJar = createTestSourceJar(
            "com/example/Test.java" to "package com.example; class Test {}",
        )

        val firstResult = extractor.extractAndIndex(sourceJar)
        val secondResult = extractor.extractAndIndex(sourceJar)

        // Should return same cached result
        assertEquals(firstResult, secondResult)
    }

    @Test
    fun `extractAndIndex - returns empty for nonexistent jar`() {
        val result = extractor.extractAndIndex(tempDir.resolve("nonexistent.jar"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractAndIndex - rejects zip slip entries`() {
        val sourceJar = createTestSourceJar(
            "../../evil.java" to "class Evil {}",
            "safe/Ok.java" to "package safe; class Ok {}",
        )

        val extractionRoot = tempDir.resolve("extracted")
        val outsidePath = extractionRoot.parent.resolve("evil.java")

        val index = extractor.extractAndIndex(sourceJar)

        assertTrue(index.containsKey("safe.Ok"))
        assertTrue(Files.exists(index["safe.Ok"]))
        assertTrue(Files.notExists(outsidePath))
    }

    @Test
    fun `findSourceForClass - finds extracted class`() {
        val sourceJar = createTestSourceJar(
            "org/jenkins/Step.java" to "package org.jenkins; class Step {}",
        )

        extractor.extractAndIndex(sourceJar)

        val found = extractor.findSourceForClass("org.jenkins.Step")
        assertNotNull(found)
        assertTrue(found.toString().endsWith("Step.java"))
    }

    @Test
    fun `findSourceForClass - handles inner classes`() {
        val sourceJar = createTestSourceJar(
            "org/example/Outer.java" to "package org.example; class Outer { class Inner {} }",
        )

        extractor.extractAndIndex(sourceJar)

        // Inner class should map to outer class source
        val found = extractor.findSourceForClass("org.example.Outer\$Inner")
        assertNotNull(found)
        assertTrue(found.toString().endsWith("Outer.java"))
    }

    @Test
    fun `findSourceForClass - returns null for unknown class`() {
        val result = extractor.findSourceForClass("com.unknown.Unknown")
        assertNull(result)
    }

    @Test
    fun `getStatistics - returns correct counts`() {
        val sourceJar = createTestSourceJar(
            "com/a/A.java" to "class A {}",
            "com/b/B.java" to "class B {}",
        )

        extractor.extractAndIndex(sourceJar)

        val stats = extractor.getStatistics()
        assertEquals(1, stats["extractedJars"])
        assertEquals(2, stats["indexedClasses"])
    }

    // Helper to create test source JARs
    private fun createTestSourceJar(vararg files: Pair<String, String>): Path {
        val jarPath = tempDir.resolve("test-sources.jar")

        ZipOutputStream(Files.newOutputStream(jarPath)).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }

        return jarPath
    }
}
