package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Compiles read the workspace configuration from other threads while it is replaced. Every read must observe one
 * whole configuration, never a list being cleared and refilled, and nothing handed out may change later (H1).
 */
class WorkspaceModelConsistencyTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a strategy never sees a later classpath change through the list it was handed`() {
        val first = listOf(tempDir.resolve("a.jar"))
        val handed = ConcurrentLinkedQueue<List<Path>>()
        val manager = WorkspaceManager()
        manager.setStrategyRegistry(registryWith(capturing(handed)))
        manager.updateDependencies(first)

        manager.getClasspathForFile(URI.create("file:///Script.groovy"), "")
        manager.updateDependencies(listOf(tempDir.resolve("b.jar")))

        assertEquals(first, handed.single(), "the classpath a compile was given changed under it")
    }

    @Test
    fun `concurrent readers observe whole workspace configurations`() {
        val root = Files.createDirectories(tempDir.resolve("ws"))
        val rootsA = (1..20).map { Files.createDirectories(root.resolve("a$it")) }
        val rootsB = (1..5).map { Files.createDirectories(root.resolve("b$it")) }
        val depsA = (1..20).map { root.resolve("a$it.jar") }
        val depsB = (1..5).map { root.resolve("b$it.jar") }
        val manager = WorkspaceManager()
        manager.updateWorkspaceModel(root, depsA, rootsA)
        val fingerprints = setOf(
            ConfigurationFingerprint.compute(depsA, rootsA),
            ConfigurationFingerprint.compute(depsB, rootsB),
        )

        val failures = ConcurrentLinkedQueue<String>()
        val stop = AtomicBoolean()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(READERS + 1)
        try {
            pool.execute {
                try {
                    start.await()
                    repeat(UPDATES) { i ->
                        if (i % 2 == 0) {
                            manager.updateWorkspaceModel(root, depsB, rootsB)
                        } else {
                            manager.updateWorkspaceModel(root, depsA, rootsA)
                        }
                    }
                } finally {
                    stop.set(true) // readers stop even if an update throws
                }
            }
            repeat(READERS) {
                pool.execute {
                    start.await()
                    while (!stop.get() && !Thread.currentThread().isInterrupted) {
                        runCatching {
                            val roots = manager.getSourceRoots().toSet()
                            if (roots != rootsA.toSet() && roots != rootsB.toSet()) {
                                failures += "torn source roots: ${roots.size}"
                            }
                            val deps = manager.getDependencyClasspath().toSet()
                            if (deps != depsA.toSet() &&
                                deps != depsB.toSet()
                            ) {
                                failures += "torn classpath: ${deps.size}"
                            }
                            if (manager.getConfigurationFingerprint() !in fingerprints) failures += "torn fingerprint"
                        }.onFailure { failures += it.toString() }
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "workers did not finish")
        } finally {
            pool.shutdownNow()
        }

        assertTrue(failures.isEmpty(), "${failures.size} inconsistent reads, e.g. ${failures.take(3)}")
    }

    @Test
    fun `a reordered classpath is a change with a new fingerprint`() {
        val root = Files.createDirectories(tempDir.resolve("ordered"))
        val deps = listOf(root.resolve("a.jar"), root.resolve("b.jar"))
        val manager = WorkspaceManager()
        manager.updateWorkspaceModel(root, deps, emptyList())
        val before = manager.getConfigurationFingerprint()

        // The JVM loads the first match: the same jars in another order can resolve different classes.
        assertEquals(true, manager.updateWorkspaceModel(root, deps.reversed(), emptyList()))
        assertNotEquals(before, manager.getConfigurationFingerprint())
        assertEquals(deps.reversed(), manager.getDependencyClasspath())
        assertEquals(true, manager.updateDependencies(deps), "updateDependencies follows the same rule")
    }

    @Test
    fun `reordered source roots are unchanged and keep their fingerprint`() {
        val root = Files.createDirectories(tempDir.resolve("roots"))
        val roots = listOf(Files.createDirectories(root.resolve("a")), Files.createDirectories(root.resolve("b")))
        val manager = WorkspaceManager()
        manager.updateWorkspaceModel(root, emptyList(), roots)
        val before = manager.getConfigurationFingerprint()

        assertEquals(false, manager.updateWorkspaceModel(root, emptyList(), roots.reversed()))
        assertEquals(before, manager.getConfigurationFingerprint(), "unchanged reported, but the fingerprint moved")
    }

    private fun capturing(handed: MutableCollection<List<Path>>) = object : ProjectStrategy {
        override val id = "capturing"
        override val displayName = "Capturing"
        override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
        override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
        override fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>): List<Path>? {
            handed += projectDependencies // retained, as a strategy caching per-file classpaths would
            return null
        }
    }

    private fun registryWith(strategy: ProjectStrategy) = ProjectStrategyRegistry(listOf(strategy)).also {
        it.selectStrategies(tempDir, ServerConfiguration())
    }

    private companion object {
        const val READERS = 4
        const val UPDATES = 400
        const val WAIT_SECONDS = 60L
    }
}
