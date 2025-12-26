package com.github.albertocavalcante.groovylsp.version

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GroovyVersionResolverTest {

    @Test
    fun `override version wins over dependencies`() {
        val resolver = GroovyVersionResolver { "4.0.0" }
        val info = resolver.resolve(
            dependencies = listOf(Path.of("lib/groovy-2.5.2.jar")),
            overrideVersion = "3.0.9",
        )

        assertEquals(GroovyVersionSource.OVERRIDE, info.source)
        assertEquals("3.0.9", info.version.raw)
    }

    @Test
    fun `highest dependency version is selected`() {
        val resolver = GroovyVersionResolver { "4.0.0" }
        val info = resolver.resolve(
            dependencies = listOf(
                Path.of("lib/groovy-2.4.21.jar"),
                Path.of("lib/groovy-3.0.9.jar"),
            ),
            overrideVersion = null,
        )

        assertEquals(GroovyVersionSource.DEPENDENCY, info.source)
        assertEquals("3.0.9", info.version.raw)
    }

    @Test
    fun `indy suffix is ignored`() {
        val resolver = GroovyVersionResolver { "4.0.0" }
        val info = resolver.resolve(
            dependencies = listOf(Path.of("lib/groovy-4.0.29-indy.jar")),
            overrideVersion = null,
        )

        assertEquals("4.0.29", info.version.raw)
    }

    @Test
    fun `fallback to runtime version when no dependencies`() {
        val resolver = GroovyVersionResolver { "2.4.21" }
        val info = resolver.resolve(dependencies = emptyList(), overrideVersion = null)

        assertEquals(GroovyVersionSource.RUNTIME, info.source)
        assertEquals("2.4.21", info.version.raw)
    }

    @Test
    fun `invalid override is ignored`() {
        val resolver = GroovyVersionResolver { "4.0.0" }
        val info = resolver.resolve(
            dependencies = listOf(Path.of("lib/groovy-3.0.9.jar")),
            overrideVersion = "bogus",
        )

        assertEquals(GroovyVersionSource.DEPENDENCY, info.source)
        assertNotNull(info.version)
    }
}
