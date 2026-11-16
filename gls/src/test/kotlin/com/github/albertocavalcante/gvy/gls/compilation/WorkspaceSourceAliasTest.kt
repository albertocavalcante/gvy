// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two configured source roots can name one directory: a root plus a link to it, or the same directory in another
 * case on a case-insensitive file system. The scan walks that directory once, under the first such root in order,
 * and events spelled through any alias land on that root's spelling (#1431). The published roots, and so the
 * fingerprint, stay as configured. Case is simulated with an injected probe, as in [WorkspaceSourceCanonicalTest].
 */
class WorkspaceSourceAliasTest {
    @TempDir
    lateinit var tempDir: Path

    private val base: Path by lazy { tempDir.toRealPath() }
    private val workspace: Path by lazy { Files.createDirectories(base.resolve("ws")) }

    private fun manager(caseInsensitive: Boolean = false) =
        WorkspaceManager(ConfigurationFingerprint::compute, { caseInsensitive })

    private fun write(path: Path, text: String = "class X {}\n"): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    /** `ws/src` with [files], and `ws/alias`, a link to it inside the workspace. */
    private fun aliased(vararg files: String): Pair<Path, Path> {
        val src = Files.createDirectories(workspace.resolve("src"))
        files.forEach { write(src.resolve(it)) }
        return src to Files.createSymbolicLink(workspace.resolve("alias"), src)
    }

    @Test
    fun `a root and a link to it list each file once, under the first root`() {
        val (src, alias) = aliased("A.groovy", "pkg/B.groovy")

        val linkLast = manager().apply { updateWorkspaceModel(workspace, emptyList(), listOf(src, alias)) }
        assertEquals(listOf(src.resolve("A.groovy"), src.resolve("pkg/B.groovy")), linkLast.getWorkspaceSources())

        val linkFirst = manager().apply { updateWorkspaceModel(workspace, emptyList(), listOf(alias, src)) }
        assertEquals(
            listOf(alias.resolve("A.groovy"), alias.resolve("pkg/B.groovy")),
            linkFirst.getWorkspaceSources(),
            "the first root's spelling wins",
        )
    }

    @Test
    fun `an alias root spelled with dot segments is still recognised as the same directory`() {
        val (src, alias) = aliased("A.groovy")
        val dotted = src.resolve("..").resolve(alias.fileName)
        val manager = manager().apply { updateWorkspaceModel(workspace, emptyList(), listOf(src, dotted)) }
        assertEquals(listOf(src.resolve("A.groovy")), manager.getWorkspaceSources(), "scan")

        write(src.resolve("B.groovy"))
        manager.applySourceChanges(listOf(dotted.resolve("B.groovy")), emptyList())
        assertEquals(listOf(src.resolve("A.groovy"), src.resolve("B.groovy")), manager.getWorkspaceSources(), "event")
    }

    @Test
    fun `an event crossing a linked directory before a parent segment cannot add or remove a source`() {
        val src = Files.createDirectories(workspace.resolve("src"))
        val outside = Files.createDirectories(base.resolve("outside/child"))
        val link = Files.createSymbolicLink(src.resolve("link"), outside)
        val manager = manager().apply { updateWorkspaceModel(workspace, emptyList(), listOf(src)) }
        val ordinary = write(src.resolve("B.groovy"))
        val traversal = link.resolve("../B.groovy")

        assertEquals(SourceListChange.NONE, manager.applySourceChanges(listOf(traversal), emptyList()))
        assertTrue(manager.getWorkspaceSources().isEmpty())
        assertEquals(listOf(ordinary), manager.applySourceChanges(listOf(ordinary), emptyList()).added)
        assertEquals(SourceListChange.NONE, manager.applySourceChanges(emptyList(), listOf(traversal)))
        assertEquals(listOf(ordinary), manager.getWorkspaceSources())
    }

    @Test
    fun `on a case-insensitive file system roots differing only in case list each file once`() {
        val src = Files.createDirectories(workspace.resolve("src"))
        // Distinct directories on case-sensitive Linux; one directory on a case-insensitive disk.
        val upper = Files.createDirectories(workspace.resolve("SRC"))
        listOf(src, upper).forEach { write(it.resolve("A.groovy")) }
        val manager = manager(caseInsensitive = true)

        manager.updateWorkspaceModel(workspace, emptyList(), listOf(src, upper))

        assertEquals(listOf(src.resolve("A.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `on a case-sensitive file system roots differing only in case stay distinct`() {
        val src = Files.createDirectories(workspace.resolve("src"))
        val upper = workspace.resolve("SRC")
        Assumptions.assumeFalse(Files.exists(upper), "a case-insensitive disk cannot hold both directories")
        Files.createDirectories(upper)
        listOf(src, upper).forEach { write(it.resolve("A.groovy")) }
        val manager = manager(caseInsensitive = false)

        manager.updateWorkspaceModel(workspace, emptyList(), listOf(src, upper))

        assertEquals(listOf(src.resolve("A.groovy"), upper.resolve("A.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `aliased roots are still published as configured, so the fingerprint is unchanged`() {
        val (src, alias) = aliased("A.groovy")
        val manager = manager()
        manager.updateWorkspaceModel(workspace, emptyList(), listOf(src, alias))
        val fingerprint = manager.getConfigurationFingerprint()

        assertEquals(listOf(src, alias), manager.getSourceRoots())
        assertEquals(ConfigurationFingerprint.compute(emptyList(), listOf(src, alias)), fingerprint)
        assertFalse(manager.updateWorkspaceModel(workspace, emptyList(), listOf(src, alias)), "same configuration")
        assertEquals(fingerprint, manager.getConfigurationFingerprint())
    }

    @Test
    fun `a file created and deleted through an alias root joins and leaves once in the surviving spelling`() {
        val (src, alias) = aliased()
        val manager = manager().apply { updateWorkspaceModel(workspace, emptyList(), listOf(src, alias)) }
        write(src.resolve("A.groovy"))

        val created = manager.applySourceChanges(listOf(alias.resolve("A.groovy")), emptyList())
        assertEquals(listOf(src.resolve("A.groovy")), created.added, "a new file is a change")
        assertEquals(listOf(src.resolve("A.groovy")), manager.getWorkspaceSources(), "created")

        // A listed file reported again, in either spelling, is no change: no needless cache clear.
        listOf(src, alias).forEach { root ->
            val again = manager.applySourceChanges(listOf(root.resolve("A.groovy")), emptyList())
            assertEquals(SourceListChange.NONE, again, "created again as ${root.fileName}")
        }
        assertEquals(listOf(src.resolve("A.groovy")), manager.getWorkspaceSources(), "still once")

        Files.delete(src.resolve("A.groovy"))
        manager.applySourceChanges(emptyList(), listOf(alias.resolve("A.groovy")))
        assertEquals(emptyList(), manager.getWorkspaceSources(), "deleted")
    }

    @Test
    fun `a file listed through an alias leaves when deleted through it`() {
        val (src, alias) = aliased("A.groovy")
        val manager = manager().apply { updateWorkspaceModel(workspace, emptyList(), listOf(src, alias)) }

        Files.delete(src.resolve("A.groovy"))
        manager.applySourceChanges(emptyList(), listOf(alias.resolve("A.groovy")))

        assertEquals(emptyList(), manager.getWorkspaceSources())
    }

    @Test
    fun `a folder created through an alias root is walked into the surviving spelling`() {
        val (src, alias) = aliased()
        val manager = manager().apply { updateWorkspaceModel(workspace, emptyList(), listOf(src, alias)) }
        write(src.resolve("pkg/deep/B.groovy"))

        manager.applySourceChanges(listOf(alias.resolve("pkg")), emptyList())

        assertEquals(listOf(src.resolve("pkg/deep/B.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `an event spelled by the real path of a linked first root lands on the link spelling`() {
        val real = Files.createDirectories(workspace.resolve("real/src"))
        val link = Files.createSymbolicLink(workspace.resolve("src"), real)
        val manager = manager().apply { updateWorkspaceModel(workspace, emptyList(), listOf(link, real)) }
        write(real.resolve("A.groovy"))

        manager.applySourceChanges(listOf(real.resolve("A.groovy")), emptyList())

        assertEquals(listOf(link.resolve("A.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `once the surviving root is removed from the configuration the alias takes over in its own spelling`() {
        val (src, alias) = aliased("A.groovy")
        val manager = manager().apply { updateWorkspaceModel(workspace, emptyList(), listOf(src, alias)) }
        assertEquals(listOf(src.resolve("A.groovy")), manager.getWorkspaceSources(), "both configured")

        assertTrue(manager.updateWorkspaceModel(workspace, emptyList(), listOf(alias)), "the roots changed")
        assertEquals(listOf(alias), manager.getSourceRoots())
        assertEquals(listOf(alias.resolve("A.groovy")), manager.getWorkspaceSources(), "rescanned as the alias")

        write(src.resolve("B.groovy"))
        manager.applySourceChanges(listOf(src.resolve("B.groovy")), emptyList())
        assertEquals(
            listOf(alias.resolve("A.groovy"), alias.resolve("B.groovy")),
            manager.getWorkspaceSources(),
            "an event spelled by the removed root lands on the alias",
        )
    }

    @Test
    fun `a refused link root never displaces the trusted root it would alias`() {
        write(workspace.resolve("Top.groovy"))
        // A link to the workspace itself is refused at the trust boundary; the workspace as a plain root is not.
        val refused = Files.createSymbolicLink(workspace.resolve("loop"), workspace)
        val manager = manager()

        manager.updateWorkspaceModel(workspace, emptyList(), listOf(refused, workspace))

        assertEquals(listOf(workspace.resolve("Top.groovy")), manager.getWorkspaceSources())
    }
}
