package com.github.groovylsp.bsp.model

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.Path

class BuildTargetCacheTest {

    private lateinit var cache: BuildTargetCache

    @BeforeEach
    fun setup() {
        cache = BuildTargetCache()
    }

    @Test
    fun `initially empty cache returns empty lists`() {
        assertThat(cache.all()).isEmpty()
        assertThat(cache.size()).isZero()
    }

    @Test
    fun `updateTargets adds new targets to cache`() {
        val targets = listOf(
            createTarget("target1"),
            createTarget("target2"),
        )

        cache.updateTargets(targets)

        assertThat(cache.all()).hasSize(2)
        assertThat(cache.size()).isEqualTo(2)
        assertThat(cache.contains(BuildTargetIdentifier("target1"))).isTrue()
        assertThat(cache.contains(BuildTargetIdentifier("target2"))).isTrue()
    }

    @Test
    fun `updateTargets replaces existing targets`() {
        val firstUpdate = listOf(createTarget("target1"))
        cache.updateTargets(firstUpdate)

        val secondUpdate = listOf(
            createTarget("target1"), // Updated version
            createTarget("target2"), // New target
        )
        cache.updateTargets(secondUpdate)

        assertThat(cache.all()).hasSize(2)
        assertThat(cache.contains(BuildTargetIdentifier("target1"))).isTrue()
        assertThat(cache.contains(BuildTargetIdentifier("target2"))).isTrue()
    }

    @Test
    fun `updateTargets removes targets not in new list`() {
        val firstUpdate = listOf(
            createTarget("target1"),
            createTarget("target2"),
            createTarget("target3"),
        )
        cache.updateTargets(firstUpdate)

        val secondUpdate = listOf(
            createTarget("target1"), // Kept
            createTarget("target3"), // Kept
        )
        cache.updateTargets(secondUpdate)

        assertThat(cache.all()).hasSize(2)
        assertThat(cache.contains(BuildTargetIdentifier("target1"))).isTrue()
        assertThat(cache.contains(BuildTargetIdentifier("target2"))).isFalse()
        assertThat(cache.contains(BuildTargetIdentifier("target3"))).isTrue()
    }

    @Test
    fun `updateSources stores source files for target`() {
        val target = createTarget("target1")
        cache.updateTargets(listOf(target))

        val sources = listOf(
            Path("/src/Main.groovy"),
            Path("/src/Utils.groovy"),
        )
        cache.updateSources(target.id, sources)

        assertThat(cache.getTargetSources(target.id)).containsExactlyInAnyOrderElementsOf(sources)
    }

    @Test
    fun `updateSources builds reverse index for source lookup`() {
        val target = createTarget("target1")
        cache.updateTargets(listOf(target))

        val sources = listOf(
            Path("/src/Main.groovy"),
            Path("/src/Utils.groovy"),
        )
        cache.updateSources(target.id, sources)

        assertThat(cache.findTargetForSource(Path("/src/Main.groovy"))).isEqualTo(target)
        assertThat(cache.findTargetForSource(Path("/src/Utils.groovy"))).isEqualTo(target)
        assertThat(cache.findTargetForSource(Path("/src/Unknown.groovy"))).isNull()
    }

    @Test
    fun `updateSources replaces old sources and rebuilds index`() {
        val target = createTarget("target1")
        cache.updateTargets(listOf(target))

        val firstSources = listOf(Path("/src/Old.groovy"))
        cache.updateSources(target.id, firstSources)

        val newSources = listOf(
            Path("/src/New1.groovy"),
            Path("/src/New2.groovy"),
        )
        cache.updateSources(target.id, newSources)

        // Old source should no longer be found
        assertThat(cache.findTargetForSource(Path("/src/Old.groovy"))).isNull()

        // New sources should be found
        assertThat(cache.findTargetForSource(Path("/src/New1.groovy"))).isEqualTo(target)
        assertThat(cache.findTargetForSource(Path("/src/New2.groovy"))).isEqualTo(target)
    }

    @Test
    fun `updateClasspath stores classpath for target`() {
        val target = createTarget("target1")
        cache.updateTargets(listOf(target))

        val classpath = listOf(
            Path("/libs/lib1.jar"),
            Path("/libs/lib2.jar"),
        )
        cache.updateClasspath(target.id, classpath)

        assertThat(cache.getTargetClasspath(target.id)).containsExactlyInAnyOrderElementsOf(classpath)
    }

    @Test
    fun `getTargetDependencies returns target dependencies`() {
        val dep1 = BuildTargetIdentifier("dep1")
        val dep2 = BuildTargetIdentifier("dep2")

        val target = createTarget("target1", dependencies = listOf(dep1, dep2))
        cache.updateTargets(listOf(target))

        assertThat(cache.getTargetDependencies(target.id)).containsExactly(dep1, dep2)
    }

    @Test
    fun `getTargetDependencies returns empty list for unknown target`() {
        assertThat(cache.getTargetDependencies(BuildTargetIdentifier("unknown"))).isEmpty()
    }

    @Test
    fun `invalidate removes target and all associated data`() {
        val target = createTarget("target1")
        cache.updateTargets(listOf(target))

        val sources = listOf(Path("/src/Main.groovy"))
        cache.updateSources(target.id, sources)

        val classpath = listOf(Path("/libs/lib.jar"))
        cache.updateClasspath(target.id, classpath)

        // Verify data is present
        assertThat(cache.contains(target.id)).isTrue()
        assertThat(cache.findTargetForSource(Path("/src/Main.groovy"))).isNotNull()
        assertThat(cache.getTargetClasspath(target.id)).isNotEmpty()

        // Invalidate
        cache.invalidate(target.id)

        // Verify all data is removed
        assertThat(cache.contains(target.id)).isFalse()
        assertThat(cache.findTargetForSource(Path("/src/Main.groovy"))).isNull()
        assertThat(cache.getTargetSources(target.id)).isEmpty()
        assertThat(cache.getTargetClasspath(target.id)).isEmpty()
    }

    @Test
    fun `clear removes all cached data`() {
        val targets = listOf(
            createTarget("target1"),
            createTarget("target2"),
        )
        cache.updateTargets(targets)

        cache.updateSources(targets[0].id, listOf(Path("/src/Main.groovy")))
        cache.updateClasspath(targets[0].id, listOf(Path("/libs/lib.jar")))

        assertThat(cache.size()).isEqualTo(2)

        cache.clear()

        assertThat(cache.all()).isEmpty()
        assertThat(cache.size()).isZero()
        assertThat(cache.findTargetForSource(Path("/src/Main.groovy"))).isNull()
    }

    @Test
    fun `toString provides useful debug information`() {
        cache.updateTargets(listOf(createTarget("target1")))
        cache.updateSources(BuildTargetIdentifier("target1"), listOf(Path("/src/Main.groovy")))

        val description = cache.toString()

        assertThat(description).contains("BuildTargetCache")
        assertThat(description).contains("targets=1")
        assertThat(description).contains("sources=1")
    }

    @Test
    fun `multiple targets can share the same source file is handled correctly`() {
        // NOTE: In real BSP, a source file should only belong to one target.
        // This tests that the cache correctly handles the "last write wins" behavior
        // if multiple targets accidentally register the same source.

        val target1 = createTarget("target1")
        val target2 = createTarget("target2")
        cache.updateTargets(listOf(target1, target2))

        val sharedSource = Path("/src/Shared.groovy")

        cache.updateSources(target1.id, listOf(sharedSource))
        assertThat(cache.findTargetForSource(sharedSource)).isEqualTo(target1)

        cache.updateSources(target2.id, listOf(sharedSource))
        assertThat(cache.findTargetForSource(sharedSource)).isEqualTo(target2) // Last write wins
    }

    @Test
    fun `cache operations are thread-safe`() {
        // Basic smoke test for thread safety - the cache uses ConcurrentHashMap
        val targets = (1..10).map { createTarget("target$it") }
        cache.updateTargets(targets)

        // Concurrent reads should not throw
        val threads = (1..10).map { idx ->
            Thread {
                repeat(100) {
                    cache.contains(BuildTargetIdentifier("target$idx"))
                    cache.getTargetSources(BuildTargetIdentifier("target$idx"))
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(cache.size()).isEqualTo(10)
    }

    // ========== Helper Methods ==========

    private fun createTarget(uri: String, dependencies: List<BuildTargetIdentifier> = emptyList()): BuildTarget =
        BuildTarget(
            /* id = */
            BuildTargetIdentifier(uri),
            /* tags = */
            emptyList(),
            /* languageIds = */
            listOf("groovy"),
            /* dependencies = */
            dependencies,
            /* capabilities = */
            BuildTargetCapabilities(),
        ).apply {
            displayName = uri
        }
}
