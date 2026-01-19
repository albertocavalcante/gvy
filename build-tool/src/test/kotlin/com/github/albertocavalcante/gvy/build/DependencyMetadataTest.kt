package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DependencyMetadataTest {
    @Test
    fun `normalizeScope converts Gradle scopes`() {
        assertEquals("compile", DependencyMetadata.normalizeScope("implementation"))
        assertEquals("compile", DependencyMetadata.normalizeScope("api"))
        assertEquals("test", DependencyMetadata.normalizeScope("testImplementation"))
        assertEquals("provided", DependencyMetadata.normalizeScope("compileOnly"))
        assertEquals("runtime", DependencyMetadata.normalizeScope("runtimeOnly"))
    }

    @Test
    fun `normalizeScope converts Maven scopes`() {
        assertEquals("compile", DependencyMetadata.normalizeScope("compile"))
        assertEquals("test", DependencyMetadata.normalizeScope("test"))
        assertEquals("provided", DependencyMetadata.normalizeScope("provided"))
        assertEquals("runtime", DependencyMetadata.normalizeScope("runtime"))
        assertEquals("provided", DependencyMetadata.normalizeScope("system"))
    }

    @Test
    fun `normalizeScope handles null and empty`() {
        assertEquals("compile", DependencyMetadata.normalizeScope(null))
        assertEquals("compile", DependencyMetadata.normalizeScope(""))
    }

    @Test
    fun `normalizeScope passes through unknown scopes`() {
        assertEquals("custom", DependencyMetadata.normalizeScope("custom"))
        assertEquals("unknown", DependencyMetadata.normalizeScope("UNKNOWN"))
    }

    @Test
    fun `parseJarFileName extracts name and version correctly`() {
        // Standard cases
        assertEquals(Pair("commons-lang3", "3.12.0"), DependencyMetadata.parseJarFileName("commons-lang3-3.12.0.jar"))
        assertEquals(Pair("groovy-all", "2.5.14"), DependencyMetadata.parseJarFileName("groovy-all-2.5.14.jar"))
        assertEquals(Pair("junit", "4.13"), DependencyMetadata.parseJarFileName("junit-4.13.jar"))
    }

    @Test
    fun `parseJarFileName handles snapshot versions`() {
        assertEquals(
            Pair("slf4j-api", "2.0.0-SNAPSHOT"),
            DependencyMetadata.parseJarFileName("slf4j-api-2.0.0-SNAPSHOT.jar"),
        )
        assertEquals(
            Pair("my-lib", "1.0.0-alpha.1"),
            DependencyMetadata.parseJarFileName("my-lib-1.0.0-alpha.1.jar"),
        )
    }

    @Test
    fun `parseJarFileName handles classifier suffixes`() {
        // JARs with classifier like -sources, -javadoc will parse the version including classifier
        // This is acceptable as the version field is informational and the regex correctly identifies the pattern
        assertEquals(
            Pair("commons-lang3", "3.12.0-sources"),
            DependencyMetadata.parseJarFileName("commons-lang3-3.12.0-sources.jar"),
        )
    }

    @Test
    fun `parseJarFileName handles edge cases`() {
        // No version found
        assertEquals(Pair("mylib", "unknown"), DependencyMetadata.parseJarFileName("mylib.jar"))
        assertEquals(Pair("no-version-here", "unknown"), DependencyMetadata.parseJarFileName("no-version-here.jar"))

        // Multiple dashes in artifact name
        assertEquals(
            Pair("apache-commons-lang", "3.0"),
            DependencyMetadata.parseJarFileName("apache-commons-lang-3.0.jar"),
        )
    }

    @Test
    fun `parseJarFileName with complex version patterns`() {
        // Maven-style versions with qualifiers
        assertEquals(
            Pair("spring-core", "5.3.21+build.123"),
            DependencyMetadata.parseJarFileName("spring-core-5.3.21+build.123.jar"),
        )
        assertEquals(Pair("guava", "31.1-jre"), DependencyMetadata.parseJarFileName("guava-31.1-jre.jar"))
    }
}
