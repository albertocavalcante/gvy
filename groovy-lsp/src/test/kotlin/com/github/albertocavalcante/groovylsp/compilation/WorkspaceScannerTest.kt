package com.github.albertocavalcante.groovylsp.compilation

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceScannerTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var scanner: WorkspaceScanner
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setup() {
        scanner = WorkspaceScanner(testDispatcher)
    }

    @Test
    fun `findGroovyFiles returns all groovy files in directory`() {
        // Create test files
        val srcDir = tempDir.resolve("src").createDirectories()
        srcDir.resolve("Test1.groovy").createFile()
        srcDir.resolve("Test2.groovy").createFile()
        srcDir.resolve("Test.java").createFile() // Should be excluded

        val files = scanner.findGroovyFiles(listOf(tempDir)).toList()

        assertEquals(2, files.size)
        assertTrue(files.any { it.fileName.toString() == "Test1.groovy" })
        assertTrue(files.any { it.fileName.toString() == "Test2.groovy" })
    }

    @Test
    fun `findGroovyFiles handles gradle files`() {
        val buildFile = tempDir.resolve("build.gradle").createFile()

        val files = scanner.findGroovyFiles(listOf(tempDir)).toList()

        assertEquals(1, files.size)
        assertEquals("build.gradle", files[0].fileName.toString())
    }

    @Test
    fun `findGroovyFiles handles Jenkinsfile`() {
        val jenkinsfile = tempDir.resolve("Jenkinsfile").createFile()

        val files = scanner.findGroovyFiles(listOf(tempDir)).toList()

        assertEquals(1, files.size)
        assertEquals("Jenkinsfile", files[0].fileName.toString())
    }

    @Test
    fun `findGroovyFiles handles gvy extension`() {
        val gvyFile = tempDir.resolve("script.gvy").createFile()

        val files = scanner.findGroovyFiles(listOf(tempDir)).toList()

        assertEquals(1, files.size)
        assertEquals("script.gvy", files[0].fileName.toString())
    }

    @Test
    fun `findGroovyFiles handles gy extension`() {
        val gyFile = tempDir.resolve("script.gy").createFile()

        val files = scanner.findGroovyFiles(listOf(tempDir)).toList()

        assertEquals(1, files.size)
        assertEquals("script.gy", files[0].fileName.toString())
    }

    @Test
    fun `findGroovyFiles handles gsh extension`() {
        val gshFile = tempDir.resolve("script.gsh").createFile()

        val files = scanner.findGroovyFiles(listOf(tempDir)).toList()

        assertEquals(1, files.size)
        assertEquals("script.gsh", files[0].fileName.toString())
    }

    @Test
    fun `findGroovyFiles with pattern filters correctly`() {
        val srcDir = tempDir.resolve("src").createDirectories()
        srcDir.resolve("Test1.groovy").createFile()
        srcDir.resolve("Test2.groovy").createFile()
        srcDir.resolve("Spec1.groovy").createFile()

        val files = scanner.findGroovyFiles(listOf(tempDir), "Test*.groovy").toList()

        assertEquals(2, files.size)
        assertTrue(files.any { it.fileName.toString() == "Test1.groovy" })
        assertTrue(files.any { it.fileName.toString() == "Test2.groovy" })
        assertFalse(files.any { it.fileName.toString() == "Spec1.groovy" })
    }

    @Test
    fun `findGroovyFiles with pattern supports wildcards`() {
        val srcDir = tempDir.resolve("src").createDirectories()
        srcDir.resolve("FooSpec.groovy").createFile()
        srcDir.resolve("BarSpec.groovy").createFile()
        srcDir.resolve("Test.groovy").createFile()

        val files = scanner.findGroovyFiles(listOf(tempDir), "*Spec.groovy").toList()

        assertEquals(2, files.size)
        assertTrue(files.any { it.fileName.toString() == "FooSpec.groovy" })
        assertTrue(files.any { it.fileName.toString() == "BarSpec.groovy" })
    }

    @Test
    fun `findGroovyFiles recursively walks subdirectories`() {
        val srcDir = tempDir.resolve("src").createDirectories()
        val nestedDir = srcDir.resolve("nested").createDirectories()

        srcDir.resolve("Root.groovy").createFile()
        nestedDir.resolve("Nested.groovy").createFile()

        val files = scanner.findGroovyFiles(listOf(tempDir)).toList()

        assertEquals(2, files.size)
        assertTrue(files.any { it.fileName.toString() == "Root.groovy" })
        assertTrue(files.any { it.fileName.toString() == "Nested.groovy" })
    }

    @Test
    fun `findGroovyFiles handles non-existent directory`() {
        val nonExistent = tempDir.resolve("does-not-exist")

        val files = scanner.findGroovyFiles(listOf(nonExistent)).toList()

        assertEquals(0, files.size)
    }

    @Test
    fun `findGroovyFiles handles multiple roots`() {
        val root1 = tempDir.resolve("root1").createDirectories()
        val root2 = tempDir.resolve("root2").createDirectories()

        root1.resolve("File1.groovy").createFile()
        root2.resolve("File2.groovy").createFile()

        val files = scanner.findGroovyFiles(listOf(root1, root2)).toList()

        assertEquals(2, files.size)
        assertTrue(files.any { it.fileName.toString() == "File1.groovy" })
        assertTrue(files.any { it.fileName.toString() == "File2.groovy" })
    }

    @Test
    fun `pathsToUris converts paths to URIs`() {
        val path1 = tempDir.resolve("Test1.groovy").createFile()
        val path2 = tempDir.resolve("Test2.groovy").createFile()
        val paths = sequenceOf(path1, path2)

        val uris = scanner.pathsToUris(paths)

        assertEquals(2, uris.size)
        assertTrue(uris.all { it.scheme == "file" })
    }

    @Test
    fun `isGroovyFile returns true for groovy extension`() {
        val path = tempDir.resolve("Test.groovy").createFile()

        assertTrue(scanner.isGroovyFile(path))
    }

    @Test
    fun `isGroovyFile returns true for gradle extension`() {
        val path = tempDir.resolve("build.gradle").createFile()

        assertTrue(scanner.isGroovyFile(path))
    }

    @Test
    fun `isGroovyFile returns true for gvy extension`() {
        val path = tempDir.resolve("script.gvy").createFile()

        assertTrue(scanner.isGroovyFile(path))
    }

    @Test
    fun `isGroovyFile returns true for gy extension`() {
        val path = tempDir.resolve("script.gy").createFile()

        assertTrue(scanner.isGroovyFile(path))
    }

    @Test
    fun `isGroovyFile returns true for gsh extension`() {
        val path = tempDir.resolve("script.gsh").createFile()

        assertTrue(scanner.isGroovyFile(path))
    }

    @Test
    fun `isGroovyFile returns true for Jenkinsfile`() {
        val path = tempDir.resolve("Jenkinsfile").createFile()

        assertTrue(scanner.isGroovyFile(path))
    }

    @Test
    fun `isGroovyFile returns false for non-Groovy files`() {
        val javaFile = tempDir.resolve("Test.java").createFile()
        val txtFile = tempDir.resolve("readme.txt").createFile()

        assertFalse(scanner.isGroovyFile(javaFile))
        assertFalse(scanner.isGroovyFile(txtFile))
    }

    @Test
    fun `isGroovyFile handles case insensitive extensions`() {
        val upperCase = tempDir.resolve("Test.GROOVY").createFile()
        val mixedCase = tempDir.resolve("build.GrAdLe").createFile()

        assertTrue(scanner.isGroovyFile(upperCase))
        assertTrue(scanner.isGroovyFile(mixedCase))
    }

    @Test
    fun `isGroovyFile with URI returns true for Groovy file`() {
        val path = tempDir.resolve("Test.groovy").createFile()
        val uri = path.toUri()

        assertTrue(scanner.isGroovyFile(uri))
    }

    @Test
    fun `isGroovyFile with URI returns false for non-Groovy file`() {
        val path = tempDir.resolve("Test.java").createFile()
        val uri = path.toUri()

        assertFalse(scanner.isGroovyFile(uri))
    }
}
