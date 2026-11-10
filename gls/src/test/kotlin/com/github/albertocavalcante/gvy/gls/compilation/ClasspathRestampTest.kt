package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.ResolvedDependencyArtifact
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The re-stamp core reads only what each trigger is allowed to read (#1364). */
class ClasspathRestampTest {
    private val root = Path.of("/workspace").toAbsolutePath()
    private val classes = root.resolve("build/classes")
    private val jarStamp = ArtifactStamp(3, 1_000)
    private val directoryStamp = ArtifactStamp(10, 1_000, 2)

    private fun evidence(artifacts: List<ResolvedDependencyArtifact>) = TargetDependencyEvidence.Scoped(
        listOf(
            TargetDependencySnapshot(
                target = DependencyTarget(URI.create("test:main"), root, "main"),
                role = DependencyRole.COMPILE,
                provenance = DependencyProvenance("test", 1),
                state = TargetDependencyState.COMPLETE,
                artifacts = artifacts,
                sourceRoots = listOf(root.resolve("src/main/groovy")),
            ),
        ),
    )

    private fun jars(count: Int) =
        (0 until count).map { ResolvedDependencyArtifact(root.resolve("lib/$it.jar"), stamp = jarStamp) }

    private val directory = ResolvedDependencyArtifact(classes, stamp = directoryStamp)

    @Test
    fun `a tick over 500 jars does 500 stats and no directory walk`() {
        var stats = 0
        val statted = mutableListOf<Path>()
        val evidence = evidence(jars(JARS) + directory)

        val refreshed = ClasspathRestamp.restampFiles(evidence) { path ->
            stats++
            statted.add(path)
            EntryStat.File(if (path.endsWith("7.jar")) ArtifactStamp(4, 2_000) else jarStamp)
        }

        assertEquals(JARS, stats, "one stat per jar, none for the directory")
        assertEquals(false, classes in statted, "a directory entry is never stat'ed or walked on the tick")
        val changed = assertNotNull(refreshed, "the replaced jar must be noticed").snapshots.single().artifacts
        assertEquals(ArtifactStamp(4, 2_000), changed.single { it.path.endsWith("7.jar") }.stamp)
        assertEquals(directoryStamp, changed.single { it.path == classes }.stamp, "the directory is left alone")
    }

    @Test
    fun `a jar seen in several targets is stat'ed once`() {
        val shared = jars(1)
        val evidence = TargetDependencyEvidence.Scoped(evidence(shared).snapshots + evidence(shared).snapshots)
        var stats = 0

        ClasspathRestamp.restampFiles(evidence) {
            stats++
            EntryStat.File(jarStamp)
        }

        assertEquals(1, stats)
    }

    @Test
    fun `a tick that finds nothing changed publishes nothing`() {
        assertNull(ClasspathRestamp.restampFiles(evidence(jars(2))) { EntryStat.File(jarStamp) })
    }

    @Test
    fun `a jar that disappeared loses its stamp`() {
        val refreshed = ClasspathRestamp.restampFiles(evidence(jars(1))) { EntryStat.Missing }

        assertNull(assertNotNull(refreshed).snapshots.single().artifacts.single().stamp)
    }

    @Test
    fun `an event under a class directory selects that directory alone, without I-O`() {
        val evidence = evidence(jars(1) + directory)

        val underClasses = listOf(classes.resolve("p/A.class"))
        assertEquals(setOf(classes), ClasspathRestamp.directoriesContaining(evidence, underClasses))
        assertEquals(emptySet(), ClasspathRestamp.directoriesContaining(evidence, listOf(root.resolve("src/A.groovy"))))
    }

    @Test
    fun `an event re-walks only the directories it selected`() {
        val other = ResolvedDependencyArtifact(root.resolve("other/classes"), stamp = directoryStamp)
        val walked = mutableListOf<Path>()

        val refreshed = ClasspathRestamp.restampDirectories(evidence(listOf(directory, other)), setOf(classes)) {
            walked.add(it)
            ArtifactStamp(12, 2_000, 3)
        }

        assertEquals(listOf(classes), walked)
        val artifacts = assertNotNull(refreshed).snapshots.single().artifacts
        assertEquals(ArtifactStamp(12, 2_000, 3), artifacts.single { it.path == classes }.stamp)
        assertEquals(directoryStamp, artifacts.single { it.path == other.path }.stamp)
    }

    private companion object {
        const val JARS = 500
    }
}
