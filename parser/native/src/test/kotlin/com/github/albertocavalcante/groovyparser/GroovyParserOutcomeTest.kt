// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserSeverity
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GroovyParserFacade] reports what it gave up in [com.github.albertocavalcante.nativeapi.ParseResult.outcome]. */
class GroovyParserOutcomeTest {

    private val parser = GroovyParserFacade()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a broken workspace file is reported as excluded and nothing else is degraded`() {
        val broken = tempDir.resolve("Broken.groovy").also {
            it.toFile().writeText("class Broken { void m( }")
        }
        val helper = tempDir.resolve("Helper.groovy").also { it.toFile().writeText("class Helper {}") }

        val outcome = parser.parse(
            ParseRequest(
                uri = URI.create("file:///w/Uses.groovy"),
                content = "class Uses { Helper ref = new Helper() }",
                workspaceSources = listOf(broken, helper),
                compilePhase = Phases.CANONICALIZATION,
            ),
        ).outcome

        assertEquals(listOf(broken.toString()), outcome.excludedSources.map { it.source })
        assertTrue(outcome.excludedSources.single().reason.isNotBlank())
        assertNull(outcome.fallback)
        assertEquals(Phases.CANONICALIZATION, outcome.achievedPhase)
        assertEquals(Phases.CANONICALIZATION, outcome.requestedPhase)
    }

    @Test
    fun `a broken buffer and its broken disk copy are excluded once, as one source`() {
        val onDisk = tempDir.resolve("Foo.groovy").also {
            it.toFile().writeText("class Foo { void m( }")
        }

        val outcome = parser.parse(
            ParseRequest(
                uri = URI.create("file:///w/Uses.groovy"),
                content = "class Uses {}",
                workspaceSources = listOf(onDisk),
                openSources = mapOf(onDisk.toUri() to "class Foo { void n( }"),
                compilePhase = Phases.CANONICALIZATION,
            ),
        ).outcome

        assertEquals(1, outcome.excludedSources.size, "got: ${outcome.excludedSources}")
    }

    @Test
    fun `excluding a broken buffer also excludes its disk copy and retains healthy companions`() {
        val broken = tempDir.resolve("Broken.groovy").also { it.toFile().writeText("class Broken { void m( }") }
        val helper = tempDir.resolve("Helper.groovy").also { it.toFile().writeText("class Helper {}") }
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///w/Uses.groovy"),
                content = "class Uses { Helper helper }",
                workspaceSources = listOf(broken, helper),
                openSources = mapOf(broken.toUri() to "class Broken { void n( }"),
                compilePhase = Phases.CANONICALIZATION,
            ),
        )

        assertEquals(listOf(broken.toUri().toString()), result.outcome.excludedSources.map { it.source })
        assertNull(result.outcome.fallback)
        assertEquals(Phases.CANONICALIZATION, result.outcome.achievedPhase)
        assertEquals("Helper", result.ast?.classes?.single()?.fields?.first { it.name == "helper" }?.type?.name)
    }

    @Test
    fun `a companion transform exception excludes both copies and preserves a healthy companion`() {
        val broken = tempDir.resolve("Broken.groovy").also {
            it.toFile().writeText("@groovy.util.logging.Slf4j class Broken {}")
        }
        val helper = tempDir.resolve("Helper.groovy").also { it.toFile().writeText("class Helper {}") }
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///w/Uses.groovy"),
                content = "class Uses { Helper helper }",
                workspaceSources = listOf(broken, helper),
                openSources = mapOf(broken.toUri() to "@groovy.util.logging.Slf4j class Broken {}"),
                compilePhase = Phases.CANONICALIZATION,
            ),
        )

        assertEquals(listOf(broken.toUri().toString()), result.outcome.excludedSources.map { it.source })
        assertTrue(result.outcome.excludedSources.single().reason.contains("slf4j", ignoreCase = true))
        assertNull(result.outcome.fallback)
        assertEquals("Helper", result.ast?.classes?.single()?.fields?.first { it.name == "helper" }?.type?.name)
    }

    @Test
    fun `a transform exception is reported on its owning source`() {
        val uri = URI.create("file:///w/Broken.groovy")
        val result = parser.parse(
            ParseRequest(
                uri = uri,
                content = "@groovy.util.logging.Slf4j class Broken {}",
                locatorCandidates = setOf(uri.toString(), uri.path),
                compilePhase = Phases.CANONICALIZATION,
            ),
        )

        assertTrue(result.diagnostics.any { "slf4j" in it.message.lowercase() }, result.diagnostics.toString())
    }

    @Test
    fun `a default request reports its own transform exception over the document`() {
        val source = "@groovy.util.logging.Slf4j\r\nclass Broken {}"
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///w/Broken.groovy"),
                content = source,
                compilePhase = Phases.CANONICALIZATION,
            ),
        )

        val error = result.diagnostics.single { "slf4j" in it.message.lowercase() }
        assertEquals(Fallback.COMPILATION_FAILED, result.outcome.fallback)
        assertEquals(Phases.CONVERSION, result.outcome.achievedPhase)
        assertTrue(result.outcome.fallbackReason.orEmpty().contains("org.slf4j.Logger"))
        assertEquals(0, error.range.start.line)
        assertEquals(0, error.range.start.character)
        assertEquals(1, error.range.end.line)
        assertEquals("class Broken {}".length, error.range.end.character)
    }

    @Test
    fun `a compile that fails on a source outside the unit reports the fallback to conversion`() {
        // The broken class lives only in a source root, so it cannot be excluded; its error is not this
        // file's, so the compile fails without an error to show and is retried where no type resolves.
        tempDir.resolve("Broken.groovy").toFile().writeText("class Broken { void m( }")
        val uri = URI.create("file:///w/Uses.groovy")

        val outcome = parser.parse(
            ParseRequest(
                uri = uri,
                content = "class Uses { Broken b }",
                sourceRoots = listOf(tempDir),
                locatorCandidates = setOf(uri.toString(), uri.path),
                compilePhase = Phases.CANONICALIZATION,
            ),
        ).outcome

        assertEquals(Fallback.COMPILATION_FAILED, outcome.fallback)
        assertEquals(Phases.CANONICALIZATION, outcome.requestedPhase)
        assertEquals(Phases.CONVERSION, outcome.achievedPhase)
        assertTrue(outcome.typesUnresolved)
        assertTrue(
            outcome.fallbackReason.orEmpty().contains("Broken.groovy"),
            "reason should name the real error: ${outcome.fallbackReason}",
        )
    }

    @Test
    fun `unlocated transform failure retains its useful cause despite locator filtering`() {
        val uri = URI.create("file:///w/Logging.groovy")
        val outcome = parser.parse(
            ParseRequest(
                uri = uri,
                content = "import groovy.util.logging.Slf4j\n@Slf4j class Logging {}",
                locatorCandidates = setOf(uri.toString(), uri.path),
                compilePhase = Phases.CANONICALIZATION,
            ),
        ).outcome

        assertEquals(Fallback.COMPILATION_FAILED, outcome.fallback)
        assertTrue(outcome.fallbackReason.orEmpty().contains("org.slf4j.Logger"), "${outcome.fallbackReason}")
        assertTrue(!outcome.fallbackReason.orEmpty().contains('\n'))
    }

    @Test
    fun `logging dependency restores requested phase with broken source excluded`() {
        val uri = URI.create("file:///w/Logging.groovy")
        val broken = tempDir.resolve("Broken.groovy").also { it.toFile().writeText("class Broken { void m( }") }
        val content = "import groovy.util.logging.Slf4j\n@Slf4j class Logging {}"
        val request = ParseRequest(
            uri = uri,
            content = content,
            workspaceSources = listOf(broken),
            locatorCandidates = setOf(uri.toString(), uri.path),
            compilePhase = Phases.CANONICALIZATION,
        )
        val missing = parser.parse(request).outcome
        assertTrue(missing.fallbackReason.orEmpty().contains("org.slf4j.Logger"), "${missing.fallbackReason}")
        assertEquals(listOf(broken.toString()), missing.excludedSources.map { it.source })

        val slf4j = Path.of(org.slf4j.Logger::class.java.protectionDomain.codeSource.location.toURI())
        val restored = parser.parse(request.copy(classpath = listOf(slf4j))).outcome
        assertEquals(Phases.CANONICALIZATION, restored.achievedPhase)
        assertNull(restored.fallback)
        assertEquals(listOf(broken.toString()), restored.excludedSources.map { it.source })
    }

    @Test
    fun `a missing superclass in the file itself is an ordinary error and not a fallback`() {
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///w/S.groovy"),
                content = "import spock.lang.Specification\n\nclass S extends Specification {}",
                compilePhase = Phases.CANONICALIZATION,
            ),
        )

        assertTrue(result.diagnostics.any { it.severity == ParserSeverity.ERROR })
        assertNull(result.outcome.fallback)
        assertEquals(Phases.CANONICALIZATION, result.outcome.achievedPhase)
    }

    @Test
    fun `a clean compile reports no degradation`() {
        val outcome = parser.parse(
            ParseRequest(
                uri = URI.create("file:///w/Clean.groovy"),
                content = "class Clean { String name }",
                compilePhase = Phases.CANONICALIZATION,
            ),
        ).outcome

        assertTrue(!outcome.isDegraded)
        assertEquals(outcome.requestedPhase, outcome.achievedPhase)
    }
}
