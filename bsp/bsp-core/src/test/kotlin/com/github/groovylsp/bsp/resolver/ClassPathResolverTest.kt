package com.github.groovylsp.bsp.resolver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.Path

class ClassPathResolverTest {

    @Test
    fun `or operator returns primary when primary has entries`() {
        val primary = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/primary.jar"))),
            buildScriptClasspath = setOf(Path("/primary-build.jar")),
            currentBuildFileVersion = 1,
        )
        val fallback = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/fallback.jar"))),
            buildScriptClasspath = setOf(Path("/fallback-build.jar")),
            currentBuildFileVersion = 2,
        )

        val combined = primary or fallback

        assertThat(combined.classpath).containsExactly(ClassPathEntry(Path("/primary.jar")))
        assertThat(combined.buildScriptClasspath).containsExactly(Path("/primary-build.jar"))
        assertThat(combined.currentBuildFileVersion).isEqualTo(1)
    }

    @Test
    fun `or operator returns fallback when primary is empty`() {
        val primary = FakeResolver(
            classpath = emptySet(),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 1,
        )
        val fallback = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/fallback.jar"))),
            buildScriptClasspath = setOf(Path("/fallback-build.jar")),
            currentBuildFileVersion = 2,
        )

        val combined = primary or fallback

        assertThat(combined.classpath).containsExactly(ClassPathEntry(Path("/fallback.jar")))
        assertThat(combined.buildScriptClasspath).containsExactly(Path("/fallback-build.jar"))
        // Version should use primary's version because result is non-empty
        // WAIT - that's wrong. Let me reread the code...
        // The version check is: if (classpath.isNotEmpty() || buildScriptClasspath.isNotEmpty())
        // It checks the RESULT properties (after fallback), not primary's properties
        // Since result is non-empty, it uses primary.currentBuildFileVersion = 1
        assertThat(combined.currentBuildFileVersion).isEqualTo(1)
    }

    @Test
    fun `or operator falls back each property independently`() {
        val primary = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/primary.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 1,
        )
        val fallback = FakeResolver(
            classpath = emptySet(),
            buildScriptClasspath = setOf(Path("/fallback-build.jar")),
            currentBuildFileVersion = 2,
        )

        val combined = primary or fallback

        // Primary has classpath, so use primary.classpath
        assertThat(combined.classpath).containsExactly(ClassPathEntry(Path("/primary.jar")))
        // Primary has empty buildScriptClasspath, so use fallback.buildScriptClasspath
        assertThat(combined.buildScriptClasspath).containsExactly(Path("/fallback-build.jar"))
        // Result has non-empty properties, so use primary.currentBuildFileVersion
        assertThat(combined.currentBuildFileVersion).isEqualTo(1)
    }

    @Test
    fun `or operator can be chained for multiple fallbacks`() {
        val first = FakeResolver(emptySet(), emptySet(), 1)
        val second = FakeResolver(emptySet(), emptySet(), 2)
        val third = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/third.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 3,
        )

        val combined = first or second or third

        assertThat(combined.classpath).containsExactly(ClassPathEntry(Path("/third.jar")))
        // (first or second) has empty results, so uses second.currentBuildFileVersion=2
        // Then (first_or_second) or third has non-empty results (from third),
        // so uses (first_or_second).currentBuildFileVersion=2
        assertThat(combined.currentBuildFileVersion).isEqualTo(2)
    }

    @Test
    fun `plus operator combines all classpath entries from both resolvers`() {
        val first = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/first.jar"))),
            buildScriptClasspath = setOf(Path("/first-build.jar")),
            currentBuildFileVersion = 1,
        )
        val second = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/second.jar"))),
            buildScriptClasspath = setOf(Path("/second-build.jar")),
            currentBuildFileVersion = 2,
        )

        val combined = first + second

        assertThat(combined.classpath).containsExactlyInAnyOrder(
            ClassPathEntry(Path("/first.jar")),
            ClassPathEntry(Path("/second.jar")),
        )
        assertThat(combined.buildScriptClasspath).containsExactlyInAnyOrder(
            Path("/first-build.jar"),
            Path("/second-build.jar"),
        )
        assertThat(combined.currentBuildFileVersion).isEqualTo(2) // max of 1 and 2
    }

    @Test
    fun `plus operator takes maximum of both versions`() {
        val first = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/first.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 5,
        )
        val second = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/second.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 3,
        )

        val combined = first + second

        assertThat(combined.currentBuildFileVersion).isEqualTo(5)
    }

    @Test
    fun `plus operator works when one resolver is empty`() {
        val first = FakeResolver(emptySet(), emptySet(), 1)
        val second = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/second.jar"))),
            buildScriptClasspath = setOf(Path("/second-build.jar")),
            currentBuildFileVersion = 2,
        )

        val combined = first + second

        assertThat(combined.classpath).containsExactly(ClassPathEntry(Path("/second.jar")))
        assertThat(combined.buildScriptClasspath).containsExactly(Path("/second-build.jar"))
        assertThat(combined.currentBuildFileVersion).isEqualTo(2)
    }

    @Test
    fun `plus operator can be chained for multiple resolvers`() {
        val first = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/first.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 1,
        )
        val second = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/second.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 2,
        )
        val third = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/third.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 3,
        )

        val combined = first + second + third

        assertThat(combined.classpath).containsExactlyInAnyOrder(
            ClassPathEntry(Path("/first.jar")),
            ClassPathEntry(Path("/second.jar")),
            ClassPathEntry(Path("/third.jar")),
        )
        assertThat(combined.currentBuildFileVersion).isEqualTo(3)
    }

    @Test
    fun `or and plus operators can be combined`() {
        val primary = FakeResolver(emptySet(), emptySet(), 1)
        val fallback = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/fallback.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 2,
        )
        val additional = FakeResolver(
            classpath = setOf(ClassPathEntry(Path("/additional.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 3,
        )

        // Use fallback when primary is empty, then add additional entries
        val combined = (primary or fallback) + additional

        assertThat(combined.classpath).containsExactlyInAnyOrder(
            ClassPathEntry(Path("/fallback.jar")),
            ClassPathEntry(Path("/additional.jar")),
        )
        assertThat(combined.currentBuildFileVersion).isEqualTo(3)
    }

    @Test
    fun `plus operator handles duplicate entries by deduplicating`() {
        val jar = ClassPathEntry(Path("/shared.jar"))
        val first = FakeResolver(
            classpath = setOf(jar, ClassPathEntry(Path("/first.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 1,
        )
        val second = FakeResolver(
            classpath = setOf(jar, ClassPathEntry(Path("/second.jar"))),
            buildScriptClasspath = emptySet(),
            currentBuildFileVersion = 2,
        )

        val combined = first + second

        // Sets automatically deduplicate
        assertThat(combined.classpath).containsExactlyInAnyOrder(
            jar,
            ClassPathEntry(Path("/first.jar")),
            ClassPathEntry(Path("/second.jar")),
        )
    }

    /**
     * Fake resolver for testing composition operators.
     */
    private class FakeResolver(
        override val classpath: Set<ClassPathEntry>,
        override val buildScriptClasspath: Set<Path>,
        currentBuildFileVersion: Long,
    ) : ClassPathResolver {
        override val currentBuildFileVersion: Long = currentBuildFileVersion
    }
}
