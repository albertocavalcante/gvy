// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.version

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

    // The tests below characterise existing resolver behaviour for Groovy 5.1 and 6 jar names; they needed no
    // production change.
    @Test
    fun `characterisation - groovy 6 release jar is detected`() {
        val info = resolveSingle("lib/groovy-6.0.0.jar")

        assertEquals(GroovyVersionSource.DEPENDENCY, info.source)
        assertEquals("6.0.0", info.version.raw)
        assertEquals(6, info.version.major)
    }

    @Test
    fun `characterisation - groovy 6 pre-release jars are detected`() {
        for (version in listOf("6.0.0-alpha-1", "6.0.0-beta-2", "6.0.0-RC-3")) {
            val info = resolveSingle("lib/groovy-$version.jar")

            assertEquals(version, info.version.raw)
            assertEquals(6, info.version.major)
            assertEquals(0, info.version.minor)
        }
    }

    @Test
    fun `characterisation - groovy-all style jar names are detected for groovy 6`() {
        assertEquals("6.0.0", resolveSingle("lib/groovy-all-6.0.0.jar").version.raw)
        assertEquals("6.0.0-RC-3", resolveSingle("lib/groovy-all-6.0.0-RC-3.jar").version.raw)
    }

    @Test
    fun `characterisation - groovy 6 pre-release order is alpha then beta then rc then release`() {
        val alpha = checkNotNull(GroovyVersion.parse("6.0.0-alpha-1"))
        val beta = checkNotNull(GroovyVersion.parse("6.0.0-beta-2"))
        val rc = checkNotNull(GroovyVersion.parse("6.0.0-RC-3"))
        val release = checkNotNull(GroovyVersion.parse("6.0.0"))

        assertEquals(listOf(alpha, beta, rc, release), listOf(release, rc, alpha, beta).sorted())
    }

    @Test
    fun `characterisation - resolver picks the groovy 6 release over its release candidate`() {
        val info = GroovyVersionResolver { "4.0.0" }.resolve(
            dependencies = listOf(Path.of("lib/groovy-6.0.0-RC-3.jar"), Path.of("lib/groovy-6.0.0.jar")),
            overrideVersion = null,
        )

        assertEquals("6.0.0", info.version.raw)
    }

    @Test
    fun `characterisation - groovy 5 1 jar is detected`() {
        assertEquals("5.1.0", resolveSingle("lib/groovy-5.1.0.jar").version.raw)
    }

    private fun resolveSingle(jarPath: String): GroovyVersionInfo =
        GroovyVersionResolver { "4.0.0" }.resolve(dependencies = listOf(Path.of(jarPath)), overrideVersion = null)
}
