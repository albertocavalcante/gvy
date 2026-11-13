// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RecoveryLoaderOwnershipTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `stub correction rounds retire every discarded loader but keep the published loader live`() {
        val fixture = Fixture()
        val result = fixture.parser.parse(
            request("class Example implements com.missing.Iface {}").copy(compilePhase = Phases.CLASS_GENERATION),
        )
        assertTrue(result.outcome.stubbedClasses.contains("com.missing.Iface"))
        assertTrue(fixture.loaders.size >= 3, "Missing starts as a class and must be corrected to an interface")
        fixture.assertPublished(result)
    }

    @Test
    fun `workspace exclusion retires the failed compilation and leaves final project resource usable`() {
        val broken = Files.writeString(directory.resolve("Broken.groovy"), "class Broken { void m( }")
        val fixture = Fixture()
        val result = fixture.parser.parse(request("class Example {}").copy(workspaceSources = listOf(broken)))
        assertTrue(result.outcome.excludedSources.isNotEmpty())
        assertTrue(fixture.loaders.size >= 2)
        fixture.assertPublished(result)
    }

    @Test
    fun `earlier phase retry retires the original loader only after extracting its failure reason`() {
        Files.writeString(directory.resolve("Broken.groovy"), "class Broken { void m( }")
        val fixture = Fixture()
        val result = fixture.parser.parse(
            request("class Example { Broken value }").copy(
                sourceRoots = listOf(directory),
                locatorCandidates = setOf("/Example.groovy", "file:///Example.groovy"),
            ),
        )
        assertEquals(Fallback.COMPILATION_FAILED, result.outcome.fallback)
        assertTrue(result.outcome.fallbackReason.orEmpty().contains("Broken.groovy"))
        assertTrue(fixture.loaders.size >= 2)
        fixture.assertPublished(result)
    }

    @Test
    fun `cancellation during a replacement compile closes it and preserves exact failure with suppressed cleanup`() {
        val fixture = Fixture()
        val cancelled = CancellationException("cancel replacement")
        val cleanup = IOException("replacement close failed")
        fixture.closeFailure = { index -> if (index == 1) cleanup else null }
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                fixture.parser.parse(
                    request("class Example implements Missing {}").copy(checkCancelled = {
                        if (fixture.loaders.size == 2) throw cancelled
                    }),
                )
            },
        )
        assertEquals(2, fixture.loaders.size)
        assertTrue(fixture.loaders.all { it.closes == 1 })
        assertEquals(listOf(cleanup), cancelled.suppressed.toList())
    }

    private fun request(content: String) = ParseRequest(
        URI.create("file:///Example.groovy"),
        content,
        classpath = listOf(directory),
        compilePhase = Phases.CANONICALIZATION,
    )

    private inner class Fixture {
        val loaders = mutableListOf<CountingLoader>()
        var closeFailure: (Int) -> IOException? = { null }
        val parser = GroovyParserFacade(ClassLoader.getPlatformClassLoader()) { parent ->
            val index = loaders.size
            CountingLoader(parent) { closeFailure(index) }.also { loaders += it }
        }

        fun assertPublished(result: ParseResult) {
            val loader = result.compilationUnit.classLoader
            val final = loaders.single { it === loader }
            assertEquals(0, final.closes)
            assertTrue(loaders.filter { it !== final }.all { it.closes == 1 })
            val marker = Files.writeString(directory.resolve("selected-project-resource.txt"), "selected")
            assertNotNull(loader.getResource(marker.fileName.toString()))
            loader.close()
            assertTrue(loaders.all { it.closes == 1 })
        }
    }

    private class CountingLoader(parent: ClassLoader, private val closeFailure: () -> IOException?) :
        SdkFallbackGroovyClassLoader(parent) {
        var closes = 0
        override fun close() {
            closes++
            super.close()
            closeFailure()?.let { throw it }
        }
    }
}
