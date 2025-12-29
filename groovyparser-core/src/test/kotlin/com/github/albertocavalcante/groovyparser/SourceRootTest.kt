package com.github.albertocavalcante.groovyparser

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceRootTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("sourceroot-test")

        // Create test directory structure
        Files.createDirectories(tempDir.resolve("com/example"))
        Files.createDirectories(tempDir.resolve("com/example/sub"))

        // Create test Groovy files
        tempDir.resolve("com/example/Foo.groovy").writeText(
            """
            package com.example
            class Foo {
                def hello() { "Hello" }
            }
            """.trimIndent(),
        )

        tempDir.resolve("com/example/Bar.groovy").writeText(
            """
            package com.example
            class Bar {
                def world() { "World" }
            }
            """.trimIndent(),
        )

        tempDir.resolve("com/example/sub/Nested.groovy").writeText(
            """
            package com.example.sub
            class Nested {
                def nested() { "Nested" }
            }
            """.trimIndent(),
        )

        // Create a file with syntax error
        tempDir.resolve("com/example/Broken.groovy").writeText(
            """
            package com.example
            class Broken {
                def broken( { // syntax error
            }
            """.trimIndent(),
        )

        // Create a Gradle file
        tempDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'groovy'
            }
            """.trimIndent(),
        )
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `tryToParse parses all groovy files`() {
        val sourceRoot = SourceRoot(tempDir)
        val results = sourceRoot.tryToParse()

        // Should find .groovy and .gradle files
        assertTrue(results.isNotEmpty())
        // We have 4 .groovy files and 1 .gradle file
        assertEquals(5, results.size)
    }

    @Test
    fun `tryToParse handles successful and failed parses`() {
        val sourceRoot = SourceRoot(tempDir)
        val results = sourceRoot.tryToParse()

        val successful = results.filter { it.isSuccessful }
        val failed = results.filter { !it.isSuccessful }

        assertTrue(successful.isNotEmpty())
        assertTrue(failed.isNotEmpty()) // Broken.groovy should fail
    }

    @Test
    fun `tryToParse with specific path`() {
        val sourceRoot = SourceRoot(tempDir)

        val result = sourceRoot.tryToParse(Path.of("com/example/Foo.groovy"))

        assertTrue(result.isSuccessful)
        val cu = result.result.get()
        assertEquals(1, cu.types.size)
        assertEquals("Foo", cu.types[0].name)
    }

    @Test
    fun `parse throws on failure`() {
        val sourceRoot = SourceRoot(tempDir)

        val cu = sourceRoot.parse(Path.of("com/example/Foo.groovy"))
        assertEquals("Foo", cu.types[0].name)

        try {
            sourceRoot.parse(Path.of("com/example/Broken.groovy"))
            assertTrue(false, "Should have thrown")
        } catch (e: ParseProblemException) {
            assertTrue(e.problems.isNotEmpty())
        }
    }

    @Test
    fun `tryToParseSubdirectory parses only subdirectory`() {
        val sourceRoot = SourceRoot(tempDir)

        val results = sourceRoot.tryToParseSubdirectory(Path.of("com/example/sub"))

        assertEquals(1, results.size)
        assertTrue(results[0].isSuccessful)

        val cu = results[0].result.get()
        assertEquals("Nested", cu.types[0].name)
    }

    @Test
    fun `getCompilationUnits returns only successful parses`() {
        val sourceRoot = SourceRoot(tempDir)
        sourceRoot.tryToParse()

        val units = sourceRoot.getCompilationUnits()

        // Should not include Broken.groovy
        assertTrue(units.isNotEmpty())
        units.forEach { cu ->
            assertTrue(cu.types.isNotEmpty())
        }
    }

    @Test
    fun `getFailedParseResults returns only failed parses`() {
        val sourceRoot = SourceRoot(tempDir)
        sourceRoot.tryToParse()

        val failed = sourceRoot.getFailedParseResults()

        assertTrue(failed.isNotEmpty())
        failed.forEach { result ->
            assertFalse(result.isSuccessful)
        }
    }

    @Test
    fun `caching works correctly`() {
        val sourceRoot = SourceRoot(tempDir)
        val path = Path.of("com/example/Foo.groovy")

        assertFalse(sourceRoot.isParsed(path))
        assertEquals(0, sourceRoot.getCacheSize())

        sourceRoot.tryToParse(path)

        assertTrue(sourceRoot.isParsed(path))
        assertEquals(1, sourceRoot.getCacheSize())

        // Second parse should return cached result
        val result1 = sourceRoot.tryToParse(path)
        val result2 = sourceRoot.tryToParse(path)
        assertTrue(result1 === result2) // Same object from cache
    }

    @Test
    fun `clearCache removes all cached results`() {
        val sourceRoot = SourceRoot(tempDir)
        sourceRoot.tryToParse()

        assertTrue(sourceRoot.getCacheSize() > 0)

        sourceRoot.clearCache()

        assertEquals(0, sourceRoot.getCacheSize())
    }

    @Test
    fun `custom extensions filter`() {
        val sourceRoot = SourceRoot(tempDir)
        sourceRoot.extensions = setOf("groovy") // Only .groovy, not .gradle

        val results = sourceRoot.tryToParse()

        // Should only find .groovy files (4 total)
        assertEquals(4, results.size)
    }

    @Test
    fun `SourceRootBuilder works`() {
        val sourceRoot = SourceRootBuilder(tempDir)
            .withExtensions("groovy")
            .build()

        val results = sourceRoot.tryToParse()
        assertEquals(4, results.size)
    }

    @Test
    fun `companion factory methods work`() {
        val fromPath = SourceRoot.from(tempDir.toString())
        val fromFile = SourceRoot.from(tempDir.toFile())

        assertEquals(tempDir, fromPath.root)
        assertEquals(tempDir, fromFile.root)
    }

    @Test
    fun `extension functions work`() {
        val fromPath = tempDir.toSourceRoot()
        val fromFile = tempDir.toFile().toSourceRoot()

        assertEquals(tempDir, fromPath.root)
        assertEquals(tempDir, fromFile.root)
    }

    @Test
    fun `handles non-existent directory gracefully`() {
        val sourceRoot = SourceRoot(Path.of("/non/existent/path"))

        val results = sourceRoot.tryToParse()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `handles empty directory gracefully`() {
        val emptyDir = createTempDirectory("empty-test")
        try {
            val sourceRoot = SourceRoot(emptyDir)
            val results = sourceRoot.tryToParse()
            assertTrue(results.isEmpty())
        } finally {
            emptyDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `getParser returns the parser instance`() {
        val sourceRoot = SourceRoot(tempDir)
        val parser = sourceRoot.getParser()

        assertNotNull(parser)
    }
}
