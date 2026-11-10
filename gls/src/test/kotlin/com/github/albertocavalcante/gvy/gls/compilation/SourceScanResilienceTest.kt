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
}
