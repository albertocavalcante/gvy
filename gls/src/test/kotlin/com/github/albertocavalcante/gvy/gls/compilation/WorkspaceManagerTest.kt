// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Assumptions.assumeTrue
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

    @Test
    fun `source list change retains its two list JVM constructor`() {
        val constructor = SourceListChange::class.java.getConstructor(List::class.java, List::class.java)
        val change = constructor.newInstance(emptyList<Path>(), emptyList<Path>())
        assertFalse(change.changed)
    }

    @Test
    fun `repeated build model update reports newly discovered sources`() {
        val root = Files.createDirectories(tempDir.resolve("src"))
        workspaceManager.updateWorkspaceModel(tempDir, emptyList(), listOf(root))
        val source = Files.writeString(root.resolve("Added.groovy"), "class Added {}")

        assertTrue(workspaceManager.updateWorkspaceModel(tempDir, emptyList(), listOf(root)))
        assertTrue(source in workspaceManager.current().sources)
        assertFalse(workspaceManager.updateWorkspaceModel(tempDir, emptyList(), listOf(root)))
    }

    @Test
    fun `source root with safe parent segment is scanned`() {
        val src = Files.createDirectories(tempDir.resolve("src"))
        val source = Files.writeString(src.resolve("Example.groovy"), "class Example {}")
        val configured = src.resolve("../src")

        workspaceManager.updateWorkspaceModel(tempDir, emptyList(), listOf(configured))

        assertEquals(listOf(src), workspaceManager.getSourceRoots())
        assertEquals(listOf(source), workspaceManager.getWorkspaceSources())
    }

    @Test
    fun `parent segment across a link does not scan the normalized directory`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val src = Files.createDirectories(tempDir.resolve("src"))
        Files.writeString(src.resolve("Example.groovy"), "class Example {}")
        val nested = Files.createDirectories(tempDir.resolve("other/nested"))
        Files.createSymbolicLink(src.resolve("link"), nested)
        val configured = src.resolve("link/../src")

        workspaceManager.updateWorkspaceModel(tempDir, emptyList(), listOf(configured))

        assertTrue(workspaceManager.getSourceRoots().isEmpty())
        assertEquals(listOf(configured), workspaceManager.current().configuredSourceRoots)
        assertTrue(workspaceManager.getWorkspaceSources().isEmpty())
    }

    @Test
    fun `root admission change is reported even when the source list stays empty`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val managed = Files.createDirectories(tempDir.resolve("managed"))
        val root = tempDir.resolve("src")
        Files.createSymbolicLink(root, managed)
        val manager = WorkspaceManager { path -> path.toAbsolutePath().normalize().startsWith(managed) }
        manager.updateWorkspaceModel(tempDir, emptyList(), listOf(root))
        assertTrue(manager.getSourceRoots().isEmpty())

        Files.delete(root)
        Files.createDirectory(root)
        val change = manager.applySourceChanges(listOf(root), emptyList())

        assertEquals(listOf(root), manager.getSourceRoots())
        assertTrue(change.added.isEmpty())
        assertTrue(change.removed.isEmpty())
        assertTrue(change.changed)
        assertTrue(change.compileInputsChanged)
    }

    @Test
    fun `managed root recovers after replacement and descendant event`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val managed = Files.createDirectories(tempDir.resolve("managed"))
        val root = tempDir.resolve("src")
        Files.createSymbolicLink(root, managed)
        val manager = WorkspaceManager { path -> path.toAbsolutePath().normalize().startsWith(managed) }
        manager.updateWorkspaceModel(tempDir, emptyList(), listOf(root))
        assertTrue(manager.getSourceRoots().isEmpty())
        assertEquals(listOf(root), manager.current().configuredSourceRoots)
        assertTrue(manager.getWorkspaceSources().isEmpty())

        Files.delete(root)
        Files.createDirectory(root)
        val first = Files.writeString(root.resolve("First.groovy"), "class First {}")
        val second = Files.writeString(root.resolve("Second.groovy"), "class Second {}")
        val change = manager.applySourceChanges(listOf(first), emptyList())

        assertEquals(listOf(root), manager.getSourceRoots())
        assertEquals(listOf(first, second), manager.getWorkspaceSources())
        assertEquals(listOf(first, second), change.added)
    }

    @Test
    fun `workspace model owns its unscanned root set`() {
        val roots = mutableSetOf(tempDir.resolve("missing").toAbsolutePath())
        val model = WorkspaceModel(
            root = tempDir,
            classpath = emptyList(),
            sourceRoots = emptyList(),
            sources = emptyList(),
            unscannedRoots = roots,
            computeFingerprint = { _, _ -> "test" },
        )
        val original = roots.single()

        roots.add(tempDir.resolve("other").toAbsolutePath())

        assertEquals(setOf(original), model.unscannedRoots)
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
