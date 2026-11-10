package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.ResolvedDependencyArtifact
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class WorkspaceTargetContextTest {
    @TempDir
    lateinit var root: Path

    private val provenance = DependencyProvenance("test-producer", 1)

    private fun snapshot(id: String, sourceRoot: Path, vararg jars: String) = TargetDependencySnapshot(
        target = DependencyTarget(URI.create("test:$id"), root, id),
        role = DependencyRole.COMPILE,
        provenance = provenance,
        state = TargetDependencyState.COMPLETE,
        artifacts = jars.map { ResolvedDependencyArtifact(root.resolve(it)) },
        sourceRoots = listOf(sourceRoot),
    )

    private fun manager(targets: TargetDependencyEvidence): Pair<WorkspaceManager, Map<String, Path>> {
        val main = Files.createDirectories(root.resolve("src/main/groovy"))
        val test = Files.createDirectories(root.resolve("src/test/groovy"))
        val manager = WorkspaceManager()
        manager.updateWorkspaceModel(root, listOf(root.resolve("union.jar")), listOf(main, test), targets)
        return manager to mapOf("main" to main, "test" to test)
    }

    @Test
    fun `legacy evidence keeps today's classpath and fingerprint byte for byte`() {
        val (manager, roots) = manager(TargetDependencyEvidence.Legacy)
        val uri = roots.getValue("test").resolve("ASpec.groovy").toUri()

        val context = manager.compileContextFor(uri, "class ASpec {}")

        assertEquals(manager.getClasspathForFile(uri, "class ASpec {}"), context.classpath)
        assertEquals(manager.getConfigurationFingerprint(), context.fingerprint)
        assertSame(TargetSelection.LegacyOnly, context.selection)
    }

    @Test
    fun `a selected target supplies its own ordered classpath and fingerprint from the same snapshot`() {
        val main = root.resolve("src/main/groovy")
        val test = root.resolve("src/test/groovy")
        val evidence = TargetDependencyEvidence.Scoped(
            listOf(snapshot("main", main, "spock-2.4.jar"), snapshot("test", test, "classes", "spock-2.3.jar")),
        )
        val (manager, _) = manager(evidence)

        val testContext = manager.compileContextFor(test.resolve("ASpec.groovy").toUri(), "")
        val mainContext = manager.compileContextFor(main.resolve("A.groovy").toUri(), "")

        assertEquals(listOf(root.resolve("classes"), root.resolve("spock-2.3.jar")), testContext.classpath)
        assertEquals(listOf(root.resolve("spock-2.4.jar")), mainContext.classpath)
        assertIs<TargetSelection.Selected>(testContext.selection)
        assertNotEquals(testContext.fingerprint, mainContext.fingerprint, "Targets must not share cache keys")
        assertNotEquals(manager.getConfigurationFingerprint(), testContext.fingerprint)
        assertEquals(testContext.fingerprint, manager.fingerprintFor(test.resolve("ASpec.groovy").toUri()))
        assertEquals(mainContext.fingerprint, manager.fingerprintFor(main.resolve("A.groovy").toUri()))
        assertEquals(testContext.classpath, manager.getClasspathForFile(test.resolve("ASpec.groovy").toUri(), ""))
    }

    @Test
    fun `ambiguous or unowned documents fall back to the workspace classpath`() {
        val shared = root.resolve("src/main/groovy")
        val evidence = TargetDependencyEvidence.Scoped(
            listOf(snapshot("a", shared, "a.jar"), snapshot("b", shared, "b.jar")),
        )
        val (manager, _) = manager(evidence)

        val ambiguous = manager.compileContextFor(shared.resolve("X.groovy").toUri(), "")
        assertIs<TargetSelection.Ambiguous>(ambiguous.selection)
        assertEquals(listOf(root.resolve("union.jar")), ambiguous.classpath)
        assertEquals(manager.getConfigurationFingerprint(), ambiguous.fingerprint)

        val unowned = manager.compileContextFor(root.resolve("build.groovy").toUri(), "")
        assertSame(TargetSelection.Unowned, unowned.selection)
        assertEquals(listOf(root.resolve("union.jar")), unowned.classpath)
    }

    @Test
    fun `changed target facts are a change but a provenance-only refresh is not`() {
        val main = Files.createDirectories(root.resolve("src/main/groovy"))
        val manager = WorkspaceManager()
        manager.updateWorkspaceModel(root, emptyList(), listOf(main), TargetDependencyEvidence.Legacy)
        val first = TargetDependencyEvidence.Scoped(listOf(snapshot("main", main, "x.jar")))

        assertEquals(true, manager.updateWorkspaceModel(root, emptyList(), listOf(main), first))

        val sameFacts = TargetDependencyEvidence.Scoped(
            listOf(
                TargetDependencySnapshot(
                    first.snapshots.single().target,
                    DependencyRole.COMPILE,
                    DependencyProvenance("test-producer", 2),
                    TargetDependencyState.COMPLETE,
                    first.snapshots.single().artifacts,
                    first.snapshots.single().sourceRoots,
                ),
            ),
        )
        assertEquals(false, manager.updateWorkspaceModel(root, emptyList(), listOf(main), sameFacts))
        assertSame(sameFacts, manager.current().targets, "The newer provenance is still published")

        val newJar = TargetDependencyEvidence.Scoped(listOf(snapshot("main", main, "y.jar")))
        assertEquals(true, manager.updateWorkspaceModel(root, emptyList(), listOf(main), newJar))
    }

    @Test
    fun `closed file reads and writes use the same key for every selection under scoped evidence`() {
        val main = root.resolve("src/main/groovy")
        val test = root.resolve("src/test/groovy")
        val shared = Files.createDirectories(root.resolve("src/shared"))
        val evidence = TargetDependencyEvidence.Scoped(
            listOf(
                snapshot("main", main, "m.jar"),
                snapshot("test", test, "t.jar"),
                snapshot("a", shared, "a.jar"),
                snapshot("b", shared, "b.jar"),
            ),
        )
        val (manager, _) = manager(evidence)
        val documents = listOf(
            main.resolve("A.groovy"),
            test.resolve("ASpec.groovy"),
            shared.resolve("S.groovy"),
            root.resolve("build.groovy"),
        )

        documents.forEach { document ->
            val uri = document.toUri()
            val workspace = manager.current()
            assertEquals(
                manager.compileContextFor(uri, "class X {}", workspace).fingerprint,
                manager.fingerprintFor(uri, workspace),
                "$document",
            )
        }
    }

    @Test
    fun `an overriding strategy supplies the classpath and keeps the workspace key on one predicate`() {
        val main = root.resolve("src/main/groovy")
        val (manager, _) = manager(TargetDependencyEvidence.Scoped(listOf(snapshot("main", main, "m.jar"))))
        val jenkinsfile = main.resolve("Jenkinsfile.groovy").toUri()
        val nullClasspath = main.resolve("Owned.groovy").toUri()
        val registry = ProjectStrategyRegistry(listOf(FakeStrategy(setOf(jenkinsfile, nullClasspath), jenkinsfile)))
        registry.selectStrategies(root, ServerConfiguration())
        manager.setStrategyRegistry(registry)

        val overridden = manager.compileContextFor(jenkinsfile, "")
        assertEquals(listOf(root.resolve("strategy.jar")), overridden.classpath)
        assertEquals(manager.getConfigurationFingerprint(), overridden.fingerprint)
        assertSame(TargetSelection.StrategyOverride, overridden.selection)
        assertEquals(overridden.fingerprint, manager.fingerprintFor(jenkinsfile))

        val owned = manager.compileContextFor(nullClasspath, "")
        assertEquals(listOf(root.resolve("union.jar")), owned.classpath, "No strategy classpath keeps the union")
        assertEquals(manager.fingerprintFor(nullClasspath), owned.fingerprint)

        val ordinary = manager.compileContextFor(main.resolve("A.groovy").toUri(), "")
        assertIs<TargetSelection.Selected>(ordinary.selection)
    }

    private inner class FakeStrategy(private val owned: Set<URI>, private val withClasspath: URI) : ProjectStrategy {
        override val id = "fake"
        override val displayName = "Fake"

        override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true

        override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null

        override fun overridesClasspath(uri: URI) = uri in owned

        override fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>): List<Path>? =
            if (uri == withClasspath) listOf(root.resolve("strategy.jar")) else null
    }
}
