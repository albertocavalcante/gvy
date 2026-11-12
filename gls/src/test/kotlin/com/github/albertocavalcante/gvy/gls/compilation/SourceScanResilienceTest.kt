// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A source tree that cannot be fully walked (an unreadable directory, or entries vanishing during a branch
 * switch) must not abort the workspace update: the readable sources and the new classpath still land (#1314).
 */
class SourceScanResilienceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `an unreadable directory under a source root does not abort the update`() {
        val root = Files.createDirectories(tempDir.resolve("ws"))
        val sources = Files.createDirectories(root.resolve("src"))
        val readable = Files.writeString(sources.resolve("Readable.groovy"), "class Readable {}")
        val locked = Files.createDirectories(sources.resolve("locked"))
        Files.writeString(locked.resolve("Hidden.groovy"), "class Hidden {}")
        assumeTrue(Files.getFileStore(locked).supportsFileAttributeView("posix"), "needs POSIX permissions")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        assumeTrue(!Files.isReadable(locked), "running with privileges that ignore permissions")
        try {
            val manager = WorkspaceManager()
            val dependency = root.resolve("lib.jar")

            val changed = manager.updateWorkspaceModel(root, listOf(dependency), listOf(sources))

            assertTrue(changed)
            assertEquals(listOf(readable), manager.getWorkspaceSources())
            assertEquals(listOf(dependency), manager.getDependencyClasspath(), "the update was lost")
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `a source root that vanished is skipped`() {
        val root = Files.createDirectories(tempDir.resolve("ws2"))
        val kept = Files.createDirectories(root.resolve("kept"))
        val source = Files.writeString(kept.resolve("Kept.groovy"), "class Kept {}")
        val manager = WorkspaceManager()

        manager.updateWorkspaceModel(root, emptyList(), listOf(kept, root.resolve("gone")))

        assertEquals(listOf(source), manager.getWorkspaceSources())
    }

    @Test
    fun `a symlinked source file is indexed`() {
        val root = Files.createDirectories(tempDir.resolve("ws3"))
        val sources = Files.createDirectories(root.resolve("src"))
        val target = Files.writeString(
            Files.createDirectories(root.resolve("shared")).resolve("Real.groovy"),
            "class Real {}",
        )
        val link = Files.createSymbolicLink(sources.resolve("Linked.groovy"), target)
        val manager = WorkspaceManager()

        manager.updateWorkspaceModel(root, emptyList(), listOf(sources))

        assertEquals(listOf(link), manager.getWorkspaceSources(), "Files.walk followed file links; keep doing so")
    }

    @Test
    fun `entries that vanish during the walk are skipped and the walk continues`() {
        val root = Files.createDirectories(tempDir.resolve("ws4"))
        val dirs = listOf("a", "b", "c").map { name ->
            Files.createDirectories(root.resolve(name)).also {
                Files.writeString(it.resolve("$name.groovy"), "class $name {}")
            }
        }
        var first: Path? = null

        // On the first file visited, delete the other directories: the walk then reaches entries that are gone.
        val found = collectGroovySources(root) { visited ->
            if (first == null) {
                first = visited
                dirs.filterNot { visited.startsWith(it) }.forEach { it.toFile().deleteRecursively() }
            }
        }

        assertEquals(listOf(first), found, "only the file visited before the deletion remains")
    }
}
