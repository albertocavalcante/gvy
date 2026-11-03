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
            it.toFile().writeText("class Broken extends com.missing.Base {}")
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
    fun `a compile that fails on a source outside the unit reports the fallback to conversion`() {
        // The broken class lives only in a source root, so it cannot be excluded; its error is not this
        // file's, so the compile fails without an error to show and is retried where no type resolves.
        tempDir.resolve("Broken.groovy").toFile().writeText("class Broken extends com.missing.Base {}")
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
            outcome.fallbackReason.orEmpty().contains("com.missing.Base"),
            "reason should name the real error: ${outcome.fallbackReason}",
        )
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
