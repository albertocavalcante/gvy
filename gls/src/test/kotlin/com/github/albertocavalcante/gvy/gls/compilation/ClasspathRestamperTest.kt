package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.ResolvedDependencyArtifact
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * A classpath entry of an observed target that changes on disk without a build-file change refreshes that target's
 * compile key: a jar on the next tick, a class directory after a watcher event under it (#1364).
 */
class ClasspathRestamperTest {
    @TempDir
    lateinit var root: Path

    private val compilation = GroovyCompilationService()

    /** Never runs its own launches during a test: ticks and drains are driven directly. */
    private val idle = CoroutineScope(Job())
    private val main by lazy { Files.createDirectories(root.resolve("src/main/groovy")) }
    private val document by lazy { main.resolve("A.groovy").toUri() }

    @AfterEach
    fun tearDown() = idle.cancel()

    private fun restamper(stat: (Path) -> EntryStat = ClasspathRestamp::statEntry) =
        ClasspathRestamper(compilation, idle, interval = 1.hours, debounce = 1.hours, stat = stat)

    private fun evidence(vararg entries: Path) = TargetDependencyEvidence.Scoped(
        listOf(
            TargetDependencySnapshot(
                target = DependencyTarget(URI.create("test:main"), root, "main"),
                role = DependencyRole.COMPILE,
                provenance = DependencyProvenance("test", 1),
                state = TargetDependencyState.COMPLETE,
                artifacts = entries.map { ResolvedDependencyArtifact(it, stamp = ArtifactStamp.of(it)) },
                sourceRoots = listOf(main),
            ),
        ),
    )

    private fun observe(evidence: TargetDependencyEvidence) =
        compilation.updateWorkspaceModel(root, evidence.entries(), listOf(main), evidence)

    private fun TargetDependencyEvidence.entries() =
        (this as? TargetDependencyEvidence.Scoped)?.snapshots?.flatMap { it.artifacts }?.map { it.path }.orEmpty()

    private fun key() = compilation.workspaceManager.fingerprintFor(document)

    private fun write(path: Path, size: Int, modified: Long): Path {
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(size))
        return Files.setLastModifiedTime(path, FileTime.fromMillis(modified))
    }

    @Test
    fun `a jar replaced on disk changes the target key on the next tick`(): Unit = runBlocking {
        val jar = write(root.resolve("lib/dep.jar"), 3, 1_000_000)
        observe(evidence(jar))
        val before = key()

        write(jar, 5, 2_000_000)

        assertTrue(restamper().tick(), "the replaced jar was not noticed")
        assertNotEquals(before, key())
    }

    @Test
    fun `a class rewritten in a class directory changes the target key after an event under it`(): Unit = runBlocking {
        val classes = Files.createDirectories(root.resolve("build/classes"))
        val type = write(classes.resolve("p/B.class"), 3, 1_000_000)
        observe(evidence(classes))
        val before = key()
        val restamper = restamper()

        write(type, 5, 2_000_000)
        restamper.onWatchedPaths(listOf(type))

        assertTrue(restamper.drain(), "the rewritten class was not noticed")
        assertNotEquals(before, key())
    }

    @Test
    fun `a tick that finds nothing changed keeps the model`(): Unit = runBlocking {
        observe(evidence(write(root.resolve("lib/dep.jar"), 3, 1_000_000)))
        val model = compilation.workspaceManager.current()

        assertFalse(restamper().tick())
        assertSame(model, compilation.workspaceManager.current())
    }

    @Test
    fun `a re-stamp of evidence a newer observation replaced is not published`() {
        val jar = write(root.resolve("lib/dep.jar"), 3, 1_000_000)
        val stale = evidence(jar)
        observe(stale)
        write(jar, 5, 2_000_000)
        val refreshed = checkNotNull(ClasspathRestamp.restampFiles(stale, ClasspathRestamp::statEntry))
        val newer = evidence(jar, write(root.resolve("lib/other.jar"), 1, 1_000_000))
        observe(newer)

        assertFalse(compilation.restampTargets(stale, refreshed))
        assertSame(newer, compilation.workspaceManager.current().targets)
    }

    @Test
    fun `without target evidence a tick reads nothing`(): Unit = runBlocking {
        compilation.updateWorkspaceModel(root, listOf(write(root.resolve("lib/dep.jar"), 3, 1)), listOf(main))
        var stats = 0

        val counting = restamper {
            stats++
            EntryStat.Missing
        }

        assertFalse(counting.tick())
        assertEquals(0, stats)
    }

    @Test
    fun `only in-workspace class directories are watched`() {
        val inside = Files.createDirectories(root.resolve("build/classes"))
        write(inside.resolve("A.class"), 1, 1)
        val jar = write(root.resolve("lib/dep.jar"), 1, 1)
        observe(evidence(inside, jar))

        assertEquals(listOf(inside.toAbsolutePath().normalize()), restamper().watchedDirectories(root))
        assertEquals(emptyList(), restamper().watchedDirectories(null))
    }
}
