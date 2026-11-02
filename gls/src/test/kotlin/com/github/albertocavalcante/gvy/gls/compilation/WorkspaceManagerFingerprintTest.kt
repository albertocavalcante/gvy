package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies that [WorkspaceManager.getConfigurationFingerprint] is computed once per distinct
 * configuration and invalidated by every mutation of its inputs.
 */
class WorkspaceManagerFingerprintTest {

    @TempDir
    lateinit var tempDir: Path

    private val computeCount = AtomicInteger()

    private fun newManager() = WorkspaceManager { deps, roots ->
        computeCount.incrementAndGet()
        ConfigurationFingerprint.compute(deps, roots)
    }

    @Test
    fun `unchanged inputs compute the fingerprint once`() {
        val manager = newManager()
        manager.updateDependencies(listOf(Path.of("/lib/a.jar")))

        val first = manager.getConfigurationFingerprint()
        val second = manager.getConfigurationFingerprint()

        assertEquals(first, second)
        assertEquals(1, computeCount.get())
    }

    @Test
    fun `updating the classpath yields a new fingerprint`() {
        val manager = newManager()
        manager.updateDependencies(listOf(Path.of("/lib/a.jar")))
        val before = manager.getConfigurationFingerprint()

        manager.updateDependencies(listOf(Path.of("/lib/a.jar"), Path.of("/lib/b.jar")))
        val after = manager.getConfigurationFingerprint()

        assertNotEquals(before, after)
        assertEquals(2, computeCount.get())
    }

    @Test
    fun `a no-op classpath update keeps the cached fingerprint`() {
        val manager = newManager()
        manager.updateDependencies(listOf(Path.of("/lib/a.jar")))
        manager.getConfigurationFingerprint()

        manager.updateDependencies(listOf(Path.of("/lib/a.jar")))
        manager.getConfigurationFingerprint()

        assertEquals(1, computeCount.get())
    }

    @Test
    fun `updating the workspace model invalidates the fingerprint`() {
        val manager = newManager()
        val srcA = tempDir.resolve("a").also { it.toFile().mkdirs() }
        val srcB = tempDir.resolve("b").also { it.toFile().mkdirs() }

        manager.updateWorkspaceModel(tempDir, listOf(Path.of("/lib/a.jar")), listOf(srcA))
        val before = manager.getConfigurationFingerprint()

        manager.updateWorkspaceModel(tempDir, listOf(Path.of("/lib/a.jar")), listOf(srcA, srcB))
        val after = manager.getConfigurationFingerprint()

        assertNotEquals(before, after)
        assertEquals(2, computeCount.get())
    }

    @Test
    fun `initializing the workspace invalidates the fingerprint`() {
        val manager = newManager()
        val before = manager.getConfigurationFingerprint()

        tempDir.resolve("src/main/groovy").toFile().mkdirs()
        manager.initializeWorkspace(tempDir)
        val after = manager.getConfigurationFingerprint()

        assertNotEquals(before, after)
        assertEquals(2, computeCount.get())
    }
}
