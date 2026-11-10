package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WorkspaceManager.listedSpellings] runs on the LSP notification thread for every source event (#1440). A burst
 * of generated sources outside every root (`build/generated/`) must not cost a real-path disk read per file, while
 * an event spelled through a link to a root, the `:79` case of [WorkspaceSourceCanonicalTest], still translates.
 */
class ListedSpellingsCostTest {
    @TempDir
    lateinit var tempDir: Path

    private val base: Path by lazy { tempDir.toRealPath() }
    private val workspace: Path by lazy { Files.createDirectories(base.resolve("ws")) }
    private var reads = 0

    private val manager = WorkspaceManager(
        ConfigurationFingerprint::compute,
        { false },
        normalisesNames = false,
        canonicalise = { path ->
            reads++
            DocumentTargetSelector.canonical(path)
        },
    )

    private fun write(path: Path): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "class X {}\n")
    }

    @Test
    fun `5,000 generated sources outside every root cost at most one real-path read, then none`() {
        val src = Files.createDirectories(workspace.resolve("src"))
        write(src.resolve("A.groovy"))
        manager.updateWorkspaceModel(workspace, emptyList(), listOf(src))
        manager.listedSpellings(listOf(src.resolve("A.groovy"))) // the model's spelling is built once
        val generated = (0 until GENERATED).map { workspace.resolve("build/generated/G$it.groovy") }

        reads = 0
        val first = manager.listedSpellings(generated)
        assertTrue(reads <= 1, "$reads real-path reads for one directory of generated sources")
        assertEquals(generated.associateWith { it }, first, "outside every root, each keeps its own spelling")

        reads = 0
        manager.listedSpellings(generated)
        assertEquals(0, reads, "a repeat notification in the same model reads nothing")
    }

    @Test
    fun `an event spelled by the real path of a linked root translates without a disk read`() {
        val real = Files.createDirectories(workspace.resolve("real/src"))
        write(real.resolve("A.groovy"))
        val link = Files.createSymbolicLink(workspace.resolve("src"), real)
        manager.updateWorkspaceModel(workspace, emptyList(), listOf(link))
        manager.listedSpellings(listOf(link.resolve("A.groovy")))

        reads = 0
        val spelled = manager.listedSpellings(listOf(real.resolve("A.groovy")))

        assertEquals(link.resolve("A.groovy"), spelled[real.resolve("A.groovy")])
        assertEquals(0, reads, "the real path already starts with the root's real path")
    }

    @Test
    fun `a root configured by its real path still takes events spelled through a link to it`() {
        val real = Files.createDirectories(workspace.resolve("real/src"))
        write(real.resolve("A.groovy"))
        val link = Files.createSymbolicLink(workspace.resolve("src"), real)
        manager.updateWorkspaceModel(workspace, emptyList(), listOf(real))

        val spelled = manager.listedSpellings(listOf(link.resolve("A.groovy")))

        assertEquals(real.resolve("A.groovy"), spelled[link.resolve("A.groovy")])
    }

    private companion object {
        const val GENERATED = 5_000
    }
}
