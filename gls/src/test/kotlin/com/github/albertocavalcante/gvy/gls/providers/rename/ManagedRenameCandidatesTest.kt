// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManagedRenameCandidatesTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `rename ignores managed trees even when a workspace or source root contains them`() {
        val managed = root.resolve(".gls/cache/extracted-sources")
        val cached = source(managed.resolve("legacy/p/Mode.groovy"))
        val copied = source(root.resolve("src/p/Mode.groovy"))
        val registry = ExternalSourceRegistry(root.resolve("browser"), managedRoots = listOf(managed))
        val excluded: (Path) -> Boolean = { registry.isManaged(it.toUri()) }

        val walked = walkWorkspace(listOf(root), listOf(root), 100, 1_000, excluded) {}
        assertEquals(listOf(copied), walked.files)
        assertTrue(walkWorkspace(listOf(managed), listOf(managed), 100, 1_000, excluded) {}.files.isEmpty())

        val keys = FileKeys(listOf(root))
        val candidates = CandidateCollector(keys, 100, excluded).collect(
            mapOf(keys.of(cached) to cached, keys.of(copied) to copied),
            listOf(cached.toUri(), copied.toUri()),
            Walked(listOf(cached, copied), overflow = false),
        )
        assertEquals(listOf(copied), candidates.files.values.toList())
    }

    @Test
    fun `an open buffer through a managed directory alias is excluded while an unsaved ordinary buffer remains`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val managed = root.resolve("managed")
        source(managed.resolve("Mode.groovy"))
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val alias = Files.createSymbolicLink(workspace.resolve("cache-link"), managed).resolve("Mode.groovy")
        val unsaved = workspace.resolve("Unsaved.groovy")
        val registry = ExternalSourceRegistry(root.resolve("browser"), managedRoots = listOf(managed))
        val keys = FileKeys(listOf(workspace))
        val candidates = CandidateCollector(keys, 100) { registry.isManaged(it.toUri()) }.collect(
            emptyMap(),
            listOf(alias.toUri(), unsaved.toUri()),
            Walked(emptyList(), overflow = false),
        )

        assertEquals(listOf(unsaved), candidates.files.values.toList())
    }

    private fun source(path: Path) = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "new Mode()")
    }
}
