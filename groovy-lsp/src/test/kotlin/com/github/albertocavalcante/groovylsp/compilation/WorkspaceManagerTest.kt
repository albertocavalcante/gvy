package com.github.albertocavalcante.groovylsp.compilation

import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspaceManager: WorkspaceManager

    @BeforeTest
    fun setup() {
        workspaceManager = WorkspaceManager()
    }

    // Tests for getBoundedWorkspaceSources (Issue #743 - bounded workspace selection)

    @Test
    fun `getBoundedWorkspaceSources returns empty list for empty input`() {
        val result = workspaceManager.getBoundedWorkspaceSources(emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getBoundedWorkspaceSources returns empty list when no workspace sources exist`() {
        val uri = URI.create("file:///some/file.groovy")

        val result = workspaceManager.getBoundedWorkspaceSources(setOf(uri))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getBoundedWorkspaceSources filters workspace sources by URIs`() {
        // Set up workspace with source files
        val srcDir = tempDir.resolve("src/main/groovy")
        Files.createDirectories(srcDir)

        val fileA = srcDir.resolve("FileA.groovy")
        val fileB = srcDir.resolve("FileB.groovy")
        val fileC = srcDir.resolve("FileC.groovy")
        Files.writeString(fileA, "class FileA {}")
        Files.writeString(fileB, "class FileB {}")
        Files.writeString(fileC, "class FileC {}")

        workspaceManager.initializeWorkspace(tempDir)

        // Only request FileA and FileB
        val boundedUris = setOf(fileA.toUri(), fileB.toUri())
        val result = workspaceManager.getBoundedWorkspaceSources(boundedUris)

        assertEquals(2, result.size)
        assertTrue(result.contains(fileA))
        assertTrue(result.contains(fileB))
    }

    @Test
    fun `getBoundedWorkspaceSources excludes files not in bounded set`() {
        val srcDir = tempDir.resolve("src/main/groovy")
        Files.createDirectories(srcDir)

        val fileA = srcDir.resolve("FileA.groovy")
        val fileB = srcDir.resolve("FileB.groovy")
        Files.writeString(fileA, "class FileA {}")
        Files.writeString(fileB, "class FileB {}")

        workspaceManager.initializeWorkspace(tempDir)

        // Only request FileA
        val boundedUris = setOf(fileA.toUri())
        val result = workspaceManager.getBoundedWorkspaceSources(boundedUris)

        assertEquals(1, result.size)
        assertTrue(result.contains(fileA))
    }

    @Test
    fun `getBoundedWorkspaceSources handles URIs not matching workspace sources`() {
        val srcDir = tempDir.resolve("src/main/groovy")
        Files.createDirectories(srcDir)

        val existingFile = srcDir.resolve("Existing.groovy")
        Files.writeString(existingFile, "class Existing {}")

        workspaceManager.initializeWorkspace(tempDir)

        // Request a file that doesn't exist in workspace
        val nonExistentUri = URI.create("file:///nonexistent/File.groovy")
        val result = workspaceManager.getBoundedWorkspaceSources(setOf(nonExistentUri))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getBoundedWorkspaceSources gracefully handles invalid URIs`() {
        val srcDir = tempDir.resolve("src/main/groovy")
        Files.createDirectories(srcDir)

        val validFile = srcDir.resolve("Valid.groovy")
        Files.writeString(validFile, "class Valid {}")

        workspaceManager.initializeWorkspace(tempDir)

        // Mix of valid and potentially problematic URIs
        val boundedUris = setOf(
            validFile.toUri(),
            URI.create("file:///some/other/file.groovy"),
        )
        val result = workspaceManager.getBoundedWorkspaceSources(boundedUris)

        // Should still return the valid file that exists in workspace
        assertEquals(1, result.size)
        assertTrue(result.contains(validFile))
    }

    @Test
    fun `initializeWorkspace includes test groovy and bare src for Jenkins layout`() {
        val testDir = tempDir.resolve("test/groovy")
        Files.createDirectories(testDir)
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        workspaceManager.initializeWorkspace(tempDir)

        val sourceRoots = workspaceManager.getSourceRoots()
        assertTrue(sourceRoots.contains(testDir))
        assertTrue(sourceRoots.contains(srcDir))
    }

    @Test
    fun `initializeWorkspace avoids bare src when structured roots exist`() {
        val mainDir = tempDir.resolve("src/main/groovy")
        Files.createDirectories(mainDir)
        val testDir = tempDir.resolve("test/groovy")
        Files.createDirectories(testDir)

        workspaceManager.initializeWorkspace(tempDir)

        val sourceRoots = workspaceManager.getSourceRoots()
        assertTrue(sourceRoots.contains(mainDir))
        assertTrue(sourceRoots.contains(testDir))
        assertFalse(sourceRoots.contains(tempDir.resolve("src")))
    }
}
