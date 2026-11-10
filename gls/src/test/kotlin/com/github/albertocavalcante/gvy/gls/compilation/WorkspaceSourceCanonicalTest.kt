package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * A watched-file event may spell a path differently from the scanned source list: through a symlinked root's
 * real path, or in another case on a case-insensitive file system (#1424). Membership must not drift either way.
 * Case is simulated with an injected probe so the tests are deterministic on case-sensitive Linux.
 */
class WorkspaceSourceCanonicalTest {
    @TempDir
    lateinit var tempDir: Path

    private val base: Path by lazy { tempDir.toRealPath() }

    private fun manager(caseInsensitive: Boolean = false, normalisesNames: Boolean = false) =
        WorkspaceManager(ConfigurationFingerprint::compute, { caseInsensitive }, normalisesNames)

    private fun write(path: Path, text: String = "class X {}\n"): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    /**
     * `ws/src` is a link to `ws/real/src`, inside the workspace `ws`; the model's root is the link spelling unless
     * [rootIsReal].
     */
    private fun linked(manager: WorkspaceManager, rootIsReal: Boolean = false): Pair<Path, Path> {
        val real = Files.createDirectories(base.resolve("ws/real/src"))
        val link = base.resolve("ws/src")
        Files.createSymbolicLink(link, real)
        manager.updateWorkspaceModel(base.resolve("ws"), emptyList(), listOf(if (rootIsReal) real else link))
        return link to real
    }

    @Test
    fun `a file created under a symlinked root, reported by its real path, joins once in the root spelling`() {
        val manager = manager()
        val (link, real) = linked(manager)
        write(real.resolve("A.groovy"))

        manager.applySourceChanges(listOf(real.resolve("A.groovy")), emptyList())
        assertEquals(listOf(link.resolve("A.groovy")), manager.getWorkspaceSources(), "real spelling")

        manager.applySourceChanges(listOf(link.resolve("A.groovy")), emptyList())
        assertEquals(listOf(link.resolve("A.groovy")), manager.getWorkspaceSources(), "link spelling, once")
    }

    @Test
    fun `a file deleted under a symlinked root, reported by its real path, leaves the list`() {
        val manager = manager()
        val real = Files.createDirectories(base.resolve("ws/real/src"))
        write(real.resolve("A.groovy"))
        val (link, _) = linked(manager)
        assertEquals(listOf(link.resolve("A.groovy")), manager.getWorkspaceSources(), "precondition")

        Files.delete(real.resolve("A.groovy"))
        manager.applySourceChanges(emptyList(), listOf(real.resolve("A.groovy")))

        assertEquals(emptyList(), manager.getWorkspaceSources())
    }

    @Test
    fun `a folder created under a symlinked root, reported by its real path, is walked into the root spelling`() {
        val manager = manager()
        val (link, real) = linked(manager)
        write(real.resolve("pkg/deep/B.groovy"))

        manager.applySourceChanges(listOf(real.resolve("pkg")), emptyList())

        assertEquals(listOf(link.resolve("pkg/deep/B.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `a root configured by its real path takes events spelled through the link`() {
        val manager = manager()
        val (link, real) = linked(manager, rootIsReal = true)
        write(real.resolve("A.groovy"))

        manager.applySourceChanges(listOf(link.resolve("A.groovy")), emptyList())
        assertEquals(listOf(real.resolve("A.groovy")), manager.getWorkspaceSources(), "created")

        Files.delete(real.resolve("A.groovy"))
        manager.applySourceChanges(emptyList(), listOf(link.resolve("A.groovy")))
        assertEquals(emptyList(), manager.getWorkspaceSources(), "deleted")
    }

    private fun caseRoot(manager: WorkspaceManager, vararg files: String): Path {
        val src = Files.createDirectories(base.resolve("src"))
        files.forEach { write(src.resolve(it)) }
        manager.updateWorkspaceModel(base, emptyList(), listOf(src))
        return src
    }

    @Test
    fun `on a case-insensitive root a delete in another case removes the listed file`() {
        val manager = manager(caseInsensitive = true)
        val src = caseRoot(manager, "A.groovy")

        Files.delete(src.resolve("A.groovy"))
        manager.applySourceChanges(emptyList(), listOf(src.resolve("a.groovy")))

        assertEquals(emptyList(), manager.getWorkspaceSources())
    }

    @Test
    fun `on a case-insensitive root a create in another case does not duplicate the listed file`() {
        val manager = manager(caseInsensitive = true)
        // Both spellings exist on case-sensitive Linux; on a real case-insensitive disk they are one file.
        val src = caseRoot(manager, "A.groovy")
        write(src.resolve("a.groovy"))

        manager.applySourceChanges(listOf(src.resolve("a.groovy")), emptyList())

        assertEquals(listOf(src.resolve("A.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `on a case-insensitive root a case-only rename keeps one entry in the new spelling`() {
        val manager = manager(caseInsensitive = true)
        val src = caseRoot(manager, "Old.groovy")
        Files.move(src.resolve("Old.groovy"), src.resolve("OLD.groovy"))

        manager.applySourceChanges(listOf(src.resolve("OLD.groovy")), listOf(src.resolve("Old.groovy")))

        assertEquals(listOf(src.resolve("OLD.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `on a case-insensitive root a deleted folder in another case removes the sources under it`() {
        val manager = manager(caseInsensitive = true)
        val src = caseRoot(manager, "pkg/A.groovy", "pkgUtil/B.groovy")

        src.resolve("pkg").toFile().deleteRecursively()
        manager.applySourceChanges(emptyList(), listOf(src.resolve("PKG")))

        assertEquals(listOf(src.resolve("pkgUtil/B.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `on a case-sensitive root paths that differ only in case stay distinct`() {
        val manager = manager(caseInsensitive = false)
        val src = caseRoot(manager, "A.groovy")
        write(src.resolve("a.groovy"))

        manager.applySourceChanges(listOf(src.resolve("a.groovy")), listOf(src.resolve("A.GROOVY")))

        assertEquals(listOf(src.resolve("A.groovy"), src.resolve("a.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `on a case-insensitive root a name in another Unicode normalisation matches the listed file`() {
        val manager = manager(caseInsensitive = true)
        // APFS is normalisation-insensitive too: the scan may hold NFD while the client reports NFC.
        val nfd = "Cafe\u0301.groovy"
        val nfc = "Caf\u00e9.groovy"
        val src = caseRoot(manager, nfd)

        Files.delete(src.resolve(nfd))
        manager.applySourceChanges(emptyList(), listOf(src.resolve(nfc)))

        assertEquals(emptyList(), manager.getWorkspaceSources())
    }

    @Test
    fun `a root whose name has no letters cannot be probed and is treated as case-sensitive`() {
        val digits = Files.createDirectories(base.resolve("2026"))

        assertEquals(false, probeCaseInsensitive(digits))
    }

    @Test
    fun `the probe agrees with the file system on a root it can probe`() {
        val root = Files.createDirectories(base.resolve("Probe"))
        val folds = Files.exists(base.resolve("pROBE"))

        assertEquals(folds, probeCaseInsensitive(root))
    }

    @Test
    fun `a symlinked source root is scanned through the link`() {
        val manager = manager()
        write(base.resolve("ws/real/src/pkg/A.groovy"))
        val (link, _) = linked(manager)

        assertEquals(listOf(link.resolve("pkg/A.groovy")), manager.getWorkspaceSources())
    }

    /** A workspace `ws` whose source root `ws/src` is a link to [target]. */
    private fun linkedTo(manager: WorkspaceManager, target: Path): Path {
        val workspace = Files.createDirectories(base.resolve("ws"))
        val link = Files.createSymbolicLink(workspace.resolve("src"), target)
        manager.updateWorkspaceModel(workspace, emptyList(), listOf(link))
        return link
    }

    @Test
    fun `a source root linking outside the workspace is not followed`() {
        val manager = manager()
        // The shape of a cloned `src -> /` or `src -> ~`: a target outside the workspace's real path.
        val outside = write(base.resolve("elsewhere/Secret.groovy")).parent
        val link = linkedTo(manager, outside)
        assertEquals(emptyList(), manager.getWorkspaceSources(), "scan")

        write(outside.resolve("Later.groovy"))
        manager.applySourceChanges(listOf(outside.resolve("Later.groovy"), link.resolve("Later.groovy")), emptyList())
        assertEquals(emptyList(), manager.getWorkspaceSources(), "events are not rebased through it either")
    }

    @Test
    fun `a source root linking to the workspace or an ancestor of it is not followed`() {
        listOf("ws" to { b: Path -> b.resolve("ws") }, "ancestor" to { b: Path -> b }).forEach { (label, target) ->
            val manager = manager()
            val workspace = Files.createDirectories(base.resolve("ws"))
            write(workspace.resolve("Top.groovy"))
            val link = linkedTo(manager, target(base))

            assertEquals(emptyList(), manager.getWorkspaceSources(), label)
            Files.delete(link)
        }
    }

    @Test
    fun `a broken source root link lists nothing and does not fail the scan`() {
        val manager = manager()
        linkedTo(manager, base.resolve("ws/missing"))

        assertEquals(emptyList(), manager.getWorkspaceSources())
    }

    @Test
    fun `the probe says sensitive when the flipped name is a different directory`() {
        val root = Files.createDirectories(base.resolve("Twin"))
        val twin = base.resolve("tWIN")
        if (Files.exists(twin)) return // a case-insensitive disk: the twin cannot exist separately
        Files.createDirectories(twin)

        assertEquals(false, probeCaseInsensitive(root))
    }

    @Test
    fun `on a platform that normalises names a case-sensitive root still matches another normalisation`() {
        // APFS ignores Unicode normalisation even on case-sensitive volumes.
        val manager = manager(caseInsensitive = false, normalisesNames = true)
        val nfd = "Cafe\u0301.groovy"
        val src = caseRoot(manager, nfd, "A.groovy")

        Files.delete(src.resolve(nfd))
        manager.applySourceChanges(emptyList(), listOf(src.resolve("Caf\u00e9.groovy"), src.resolve("a.groovy")))

        assertEquals(listOf(src.resolve("A.groovy")), manager.getWorkspaceSources(), "case still matters")
    }
}
