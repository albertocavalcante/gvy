package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.ResolvedDependencyArtifact
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DocumentTargetSelectorTest {
    @TempDir
    lateinit var temp: Path

    private val root = Path.of("project").toAbsolutePath()
    private val provenance = DependencyProvenance("test-producer", 3)

    private fun snapshot(
        id: String,
        vararg roots: String,
        state: TargetDependencyState = TargetDependencyState.COMPLETE,
        role: DependencyRole = DependencyRole.COMPILE,
    ) = TargetDependencySnapshot(
        target = DependencyTarget(URI.create("test:$id"), root, id),
        role = role,
        provenance = provenance,
        state = state,
        artifacts = listOf(ResolvedDependencyArtifact(root.resolve("$id.jar"))),
        sourceRoots = roots.map(root::resolve),
    )

    private fun select(path: String?, vararg snapshots: TargetDependencySnapshot) =
        DocumentTargetSelector.select(path?.let(root::resolve), TargetDependencyEvidence.Scoped(snapshots.toList()))

    @Test
    fun `legacy evidence selects nothing`() {
        assertSame(
            TargetSelection.LegacyOnly,
            DocumentTargetSelector.select(root.resolve("a/A.groovy"), TargetDependencyEvidence.Legacy),
        )
    }

    @Test
    fun `the only target whose roots contain the document is selected`() {
        val main = snapshot("main", "src/main/groovy")
        val test = snapshot("test", "src/test/groovy")

        val selection = assertIs<TargetSelection.Selected>(select("src/test/groovy/p/ASpec.groovy", main, test))
        assertSame(test, selection.snapshot)
    }

    @Test
    fun `documents outside every reported root are unowned`() {
        assertSame(TargetSelection.Unowned, select("scripts/build.groovy", snapshot("main", "src/main/groovy")))
        assertSame(TargetSelection.Unowned, select(null, snapshot("main", "src/main/groovy")))
    }

    @Test
    fun `shared or nested roots across targets are ambiguous, never a first match`() {
        val a = snapshot("a", "src/shared")
        val b = snapshot("b", "src/shared")
        assertEquals(listOf(a, b), assertIs<TargetSelection.Ambiguous>(select("src/shared/X.groovy", a, b)).candidates)

        val outer = snapshot("outer", "src")
        val inner = snapshot("inner", "src/inner")
        assertIs<TargetSelection.Ambiguous>(select("src/inner/Y.groovy", outer, inner))
    }

    @Test
    fun `an owning target that is not completely enumerated is reported as such`() {
        val unresolved = snapshot("it", "src/it/groovy", state = TargetDependencyState.UNRESOLVED)
        val selection = assertIs<TargetSelection.NotComplete>(select("src/it/groovy/ItSpec.groovy", unresolved))
        assertSame(unresolved, selection.snapshot)
    }

    @Test
    fun `only compile classpaths are candidates`() {
        val runtime = snapshot("runtime", "src/test/groovy", role = DependencyRole.TEST_RUNTIME)
        assertSame(TargetSelection.Unowned, select("src/test/groovy/ASpec.groovy", runtime))
    }

    private fun realSnapshot(id: String, project: Path, vararg roots: Path) = TargetDependencySnapshot(
        target = DependencyTarget(URI.create("test:$id"), project, id),
        role = DependencyRole.COMPILE,
        provenance = provenance,
        state = TargetDependencyState.COMPLETE,
        artifacts = emptyList(),
        sourceRoots = roots.toList(),
    )

    @Test
    fun `symlinked roots and documents compare by real path`() {
        val real = Files.createDirectories(temp.resolve("real/src/main/groovy"))
        val link = Files.createSymbolicLink(temp.resolve("linked"), real)
        val document = Files.writeString(real.resolve("A.groovy"), "class A {}")
        val evidence = TargetDependencyEvidence.Scoped(listOf(realSnapshot("main", temp, link)))

        assertIs<TargetSelection.Selected>(DocumentTargetSelector.select(document, evidence), "Root reported via link")
        assertIs<TargetSelection.Selected>(DocumentTargetSelector.select(link.resolve("A.groovy"), evidence))
    }

    @Test
    fun `case variant spellings follow the file system`() {
        val main = Files.createDirectories(temp.resolve("src/main/groovy"))
        val document = Files.writeString(main.resolve("A.groovy"), "class A {}")
        val variant = temp.resolve("SRC/Main/groovy/A.groovy")
        val evidence = TargetDependencyEvidence.Scoped(listOf(realSnapshot("main", temp, main)))

        val expected = if (Files.exists(variant) && Files.isSameFile(variant, document)) {
            TargetSelection.Selected::class
        } else {
            TargetSelection.Unowned::class
        }
        assertEquals(expected, DocumentTargetSelector.select(variant, evidence)::class, "Follows FS case rules")
    }

    @Test
    fun `relative roots resolve against the project directory`() {
        val project = Files.createDirectories(temp.resolve("module"))
        val main = Files.createDirectories(project.resolve("src/main/groovy"))
        val relative = realSnapshot("main", project, Path.of("src/main/groovy"))
        val evidence = TargetDependencyEvidence.Scoped(listOf(relative))

        assertIs<TargetSelection.Selected>(DocumentTargetSelector.select(main.resolve("A.groovy"), evidence))
        val outside = temp.resolve("src/main/groovy/A.groovy")
        assertSame(TargetSelection.Unowned, DocumentTargetSelector.select(outside, evidence))
    }

    @Test
    fun `paths are compared after normalisation`() {
        val main = snapshot("main", "src/main/groovy")
        assertIs<TargetSelection.Selected>(select("src/main/groovy/../groovy/p/A.groovy", main))
        assertSame(TargetSelection.Unowned, select("src/main/groovyish/A.groovy", main), "Prefix by path segment")
    }
}
