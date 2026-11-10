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

    private fun manager(caseInsensitive: Boolean = false) =
        WorkspaceManager(ConfigurationFingerprint::compute) { caseInsensitive }

    private fun write(path: Path, text: String = "class X {}\n"): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    /** `ws/src` is a link to `real/src`; the model's root is the link spelling unless [rootIsReal]. */
    private fun linked(manager: WorkspaceManager, rootIsReal: Boolean = false): Pair<Path, Path> {
        val real = Files.createDirectories(base.resolve("real/src"))
        val link = Files.createDirectories(base.resolve("ws")).resolve("src")
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
        manager.applySourceChanges(listOf(link.resolve("A.groovy")), emptyList())

        assertEquals(listOf(link.resolve("A.groovy")), manager.getWorkspaceSources())
    }

    @Test
    fun `a file deleted under a symlinked root, reported by its real path, leaves the list`() {
        val manager = manager()
        val real = Files.createDirectories(base.resolve("real/src"))
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
}
