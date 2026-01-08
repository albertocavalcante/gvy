package com.github.albertocavalcante.groovylsp.project

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProjectStrategyRegistryTest {

    @TempDir
    lateinit var tempDir: Path

    private val defaultConfig = ServerConfiguration()

    @Test
    fun `empty registry returns empty active strategies`() {
        val registry = ProjectStrategyRegistry()
        registry.selectStrategies(tempDir, defaultConfig)
        assertTrue(registry.activeStrategies.isEmpty())
    }

    @Test
    fun `strategies are sorted by priority descending`() {
        val lowPriority = TestStrategy("low", priority = 10)
        val highPriority = TestStrategy("high", priority = 100)
        val medPriority = TestStrategy("med", priority = 50)

        val registry = ProjectStrategyRegistry(listOf(lowPriority, highPriority, medPriority))
        registry.selectStrategies(tempDir, defaultConfig)

        assertEquals(listOf("high", "med", "low"), registry.activeStrategies.map { it.id })
    }

    @Test
    fun `only strategies that canHandle are activated`() {
        val canHandle = TestStrategy("yes", canHandle = true)
        val cannotHandle = TestStrategy("no", canHandle = false)

        val registry = ProjectStrategyRegistry(listOf(canHandle, cannotHandle))
        registry.selectStrategies(tempDir, defaultConfig)

        assertEquals(1, registry.activeStrategies.size)
        assertEquals("yes", registry.activeStrategies.first().id)
    }

    @Test
    fun `findStrategy returns correct strategy by id`() {
        val strategy1 = TestStrategy("one")
        val strategy2 = TestStrategy("two")

        val registry = ProjectStrategyRegistry(listOf(strategy1, strategy2))
        registry.selectStrategies(tempDir, defaultConfig)

        assertEquals("one", registry.findStrategy("one")?.id)
        assertEquals("two", registry.findStrategy("two")?.id)
        assertNull(registry.findStrategy("three"))
    }

    @Test
    fun `findCapability returns strategy implementing capability interface`() {
        val jenkinsLike = TestCapabilityStrategy("jenkins-test")
        val regular = TestStrategy("regular")

        val registry = ProjectStrategyRegistry(listOf(jenkinsLike, regular))
        registry.selectStrategies(tempDir, defaultConfig)

        val capability = registry.findCapability<TestCapability>()
        assertEquals("test-result", capability?.testMethod())
    }

    @Test
    fun `findCapability returns null when no strategy implements capability`() {
        val regular = TestStrategy("regular")

        val registry = ProjectStrategyRegistry(listOf(regular))
        registry.selectStrategies(tempDir, defaultConfig)

        assertNull(registry.findCapability<TestCapability>())
    }

    @Test
    fun `register adds new strategy and maintains sort order`() {
        val existing = TestStrategy("existing", priority = 50)
        val registry = ProjectStrategyRegistry(listOf(existing))

        val newHighPriority = TestStrategy("new", priority = 100)
        registry.register(newHighPriority)

        registry.selectStrategies(tempDir, defaultConfig)
        assertEquals("new", registry.activeStrategies.first().id)
    }

    @Test
    fun `shutdown calls shutdown on all active strategies`() {
        val strategy1 = TestStrategy("one")
        val strategy2 = TestStrategy("two")

        val registry = ProjectStrategyRegistry(listOf(strategy1, strategy2))
        registry.selectStrategies(tempDir, defaultConfig)
        registry.shutdown()

        assertTrue(strategy1.shutdownCalled)
        assertTrue(strategy2.shutdownCalled)
        assertTrue(registry.activeStrategies.isEmpty())
    }

    @Test
    fun `multiple strategies can be active simultaneously`() {
        val jenkins = TestStrategy("jenkins", priority = 100)
        val gradle = TestStrategy("gradle", priority = 50)
        val fallback = TestStrategy("default", priority = -1000)

        val registry = ProjectStrategyRegistry(listOf(jenkins, gradle, fallback))
        registry.selectStrategies(tempDir, defaultConfig)

        assertEquals(3, registry.activeStrategies.size)
    }

    @Test
    fun `DefaultProjectStrategy always canHandle`() {
        val defaultStrategy = DefaultProjectStrategy()
        assertTrue(defaultStrategy.canHandle(tempDir, defaultConfig))
        assertEquals(Int.MIN_VALUE, defaultStrategy.priority)
        assertEquals("default", defaultStrategy.id)
    }
}

// Test helpers

private interface TestCapability {
    fun testMethod(): String
}

private class TestStrategy(
    override val id: String,
    override val priority: Int = 0,
    private val canHandle: Boolean = true,
) : ProjectStrategy {
    override val displayName: String = "Test Strategy: $id"
    var shutdownCalled = false

    override fun canHandle(workspaceRoot: Path, config: ServerConfiguration): Boolean = canHandle
    override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
    override fun shutdown() {
        shutdownCalled = true
    }
}

private class TestCapabilityStrategy(override val id: String) :
    ProjectStrategy,
    TestCapability {
    override val displayName: String = "Test Capability Strategy"
    override val priority: Int = 100

    override fun canHandle(workspaceRoot: Path, config: ServerConfiguration): Boolean = true
    override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
    override fun testMethod(): String = "test-result"
}
