package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Whether a source root lies inside the workspace decides whether a linked ancestor under it is refused (#1432).
 * On a case-insensitive volume, or a platform that normalises names, a root spelled in another case or
 * normalisation than the workspace is still inside it (#1424). Case is simulated with an injected probe and
 * distinct directories, deterministic on case-sensitive Linux.
 */
class WorkspaceCaseContainmentTest {
    @TempDir
    lateinit var tempDir: Path

    private val base: Path by lazy { tempDir.toRealPath() }

    private fun manager(caseInsensitive: Boolean, normalisesNames: Boolean = false) =
        WorkspaceManager(ConfigurationFingerprint::compute, { caseInsensitive }, normalisesNames)

    private fun write(path: Path): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "class X {}\n")
    }

    /** Under [spelled], `src/main` links to a directory outside the workspace holding `groovy/Leak.groovy`. */
    private fun outsideAncestorLink(spelled: Path): Path {
        val outside = Files.createDirectories(base.resolve("elsewhere"))
        write(outside.resolve("groovy/Leak.groovy"))
        Files.createDirectories(spelled.resolve("src"))
        Files.createSymbolicLink(spelled.resolve("src/main"), outside)
        return spelled.resolve("src/main/groovy")
    }

    @Test
    fun `a root in another case than the workspace is refused through an outside ancestor link`() {
        val workspace = Files.createDirectories(base.resolve("ws"))
        // On a case-insensitive disk `WS` is the workspace directory; on Linux it stands in for it.
        val root = outsideAncestorLink(base.resolve("WS"))
        val manager = manager(caseInsensitive = true)

        manager.updateWorkspaceModel(workspace, emptyList(), listOf(root))

        assertEquals(emptyList(), manager.getWorkspaceSources(), "the scan walked outside the workspace")
    }

    @Test
    fun `events under a root in another case with an outside ancestor link are dropped`() {
        val workspace = Files.createDirectories(base.resolve("ws"))
        val root = outsideAncestorLink(base.resolve("WS"))
        val manager = manager(caseInsensitive = true)
        manager.updateWorkspaceModel(workspace, emptyList(), listOf(root))
        val created = write(root.resolve("New.groovy"))

        manager.applySourceChanges(listOf(created), emptyList())

        assertEquals(emptyList(), manager.getWorkspaceSources())
    }

    @Test
    fun `a root in another Unicode normalisation is refused through an outside ancestor link`() {
        // The workspace is spelled NFC and the root NFD: one directory on APFS, two on Linux.
        val workspace = Files.createDirectories(base.resolve("caf\u00e9"))
        val root = outsideAncestorLink(base.resolve("cafe\u0301"))
        val manager = manager(caseInsensitive = false, normalisesNames = true)

        manager.updateWorkspaceModel(workspace, emptyList(), listOf(root))

        assertEquals(emptyList(), manager.getWorkspaceSources(), "the scan walked outside the workspace")
    }

    @Test
    fun `a workspace opened in another case contains roots spelled either way`() {
        val real = Files.createDirectories(base.resolve("ws"))
        Assumptions.assumeFalse(Files.exists(base.resolve("WS")), "a case-insensitive disk has no room for the link")
        // The client names the workspace `WS`; a link stands in for the case-insensitive disk on Linux.
        val opened = Files.createSymbolicLink(base.resolve("WS"), real)
        val leaking = outsideAncestorLink(real)
        write(real.resolve("lib/Kept.groovy"))

        listOf(leaking, opened.resolve("src/main/groovy")).forEach { root ->
            val manager = manager(caseInsensitive = true)
            manager.updateWorkspaceModel(opened, emptyList(), listOf(root))
            assertEquals(emptyList(), manager.getWorkspaceSources(), "$root walked outside the workspace")
        }
        listOf(real.resolve("lib"), opened.resolve("lib")).forEach { root ->
            val manager = manager(caseInsensitive = true)
            manager.updateWorkspaceModel(opened, emptyList(), listOf(root))
            assertEquals(listOf(root.resolve("Kept.groovy")), manager.getWorkspaceSources(), "$root refused")
        }
    }

    @Test
    fun `a root in another case without links is still scanned`() {
        val workspace = Files.createDirectories(base.resolve("ws"))
        val root = Files.createDirectories(base.resolve("WS/src"))
        write(root.resolve("A.groovy"))
        val manager = manager(caseInsensitive = true)

        manager.updateWorkspaceModel(workspace, emptyList(), listOf(root))

        assertEquals(listOf(root.resolve("A.groovy")), manager.getWorkspaceSources(), "an innocent root refused")
    }

    @Test
    fun `on a case-sensitive workspace a root in another case is outside it, as before`() {
        val workspace = Files.createDirectories(base.resolve("ws"))
        val root = outsideAncestorLink(base.resolve("WS"))
        val manager = manager(caseInsensitive = false)

        manager.updateWorkspaceModel(workspace, emptyList(), listOf(root))

        // A distinct directory outside the workspace, without links of its own: the build's choice.
        assertEquals(listOf(root.resolve("Leak.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `a sibling whose name only starts with the workspace's is not inside it`() {
        val workspace = Files.createDirectories(base.resolve("ws"))
        val root = Files.createDirectories(base.resolve("WSX/src"))
        write(root.resolve("A.groovy"))
        val manager = manager(caseInsensitive = true)

        manager.updateWorkspaceModel(workspace, emptyList(), listOf(root))

        assertEquals(listOf(root.resolve("A.groovy")), manager.getWorkspaceSources(), "compared by component")
    }
}
