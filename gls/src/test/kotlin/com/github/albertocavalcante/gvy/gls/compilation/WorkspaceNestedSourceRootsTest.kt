// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** Source roots that nest (`src` and `src/main/groovy`) list a file under the inner root once (#1441). */
class WorkspaceNestedSourceRootsTest {
    @TempDir
    lateinit var tempDir: Path

    private val workspace: Path by lazy { Files.createDirectories(tempDir.toRealPath().resolve("ws")) }
    private val outer: Path by lazy { workspace.resolve("src") }
    private val inner: Path by lazy { outer.resolve("main/groovy") }

    private fun write(path: Path): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "class X {}\n")
    }

    private fun manager(roots: List<Path>) = WorkspaceManager(ConfigurationFingerprint::compute).apply {
        updateWorkspaceModel(workspace, emptyList(), roots)
    }

    @Test
    fun `a file under a nested root is listed once whatever the root order`() {
        val top = write(outer.resolve("Top.groovy"))
        val nested = write(inner.resolve("A.groovy"))

        val outerFirst = manager(listOf(outer, inner))
        assertEquals(listOf(nested, top).sorted(), outerFirst.getWorkspaceSources().sorted())
        assertEquals(2, outerFirst.getWorkspaceSources().size)

        val innerFirst = manager(listOf(inner, outer))
        assertEquals(listOf(nested, top).sorted(), innerFirst.getWorkspaceSources().sorted())
        assertEquals(2, innerFirst.getWorkspaceSources().size)
    }

    @Test
    fun `the published roots stay as configured`() {
        write(inner.resolve("A.groovy"))

        assertEquals(listOf(outer, inner), manager(listOf(outer, inner)).getSourceRoots())
    }

    @Test
    fun `a file created and deleted under the inner root joins and leaves once`() {
        write(outer.resolve("Top.groovy"))
        val manager = manager(listOf(outer, inner))
        val created = write(inner.resolve("B.groovy"))

        manager.applySourceChanges(listOf(created), emptyList())
        assertEquals(1, manager.getWorkspaceSources().count { it == created })

        Files.delete(created)
        manager.applySourceChanges(emptyList(), listOf(created))
        assertEquals(0, manager.getWorkspaceSources().count { it == created })
    }
}
