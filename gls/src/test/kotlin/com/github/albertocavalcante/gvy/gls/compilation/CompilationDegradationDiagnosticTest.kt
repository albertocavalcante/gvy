package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ExcludedSource
import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.ParseOutcome
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompilationDegradationDiagnosticTest {

    private val mapper = CompilationResultMapper()
    private val content = "class A {}"

    private fun parsed(): ParseResult = GroovyParserFacade().parse(
        ParseRequest(uri = URI.create("file:///w/A.groovy"), content = content, compilePhase = Phases.CANONICALIZATION),
    )

    private fun degraded(excluded: List<ExcludedSource> = emptyList()) = parsed().let {
        it.copy(
            outcome = ParseOutcome(
                requestedPhase = Phases.CANONICALIZATION,
                achievedPhase = Phases.CONVERSION,
                excludedSources = excluded,
                fallback = Fallback.COMPILATION_FAILED,
                fallbackReason = "Base.groovy: unable to resolve class com.missing.Base",
            ),
        )
    }

    @Test
    fun `a clean compile gets no degradation diagnostic`() {
        assertTrue(mapper.map(parsed(), content).diagnostics.isEmpty())
    }

    @Test
    fun `a compile that fell back to conversion says types are not resolved`() {
        val diagnostic = mapper.map(degraded(), content).diagnostics.single()

        assertEquals(DEGRADED_DIAGNOSTIC_CODE, diagnostic.code.left)
        assertEquals(DiagnosticSeverity.Information, diagnostic.severity)
        assertEquals(0, diagnostic.range.start.line)
        assertEquals(0, diagnostic.range.start.character)
        assertEquals(
            "Types are not resolved in this file: compilation fell back to CONVERSION because " +
                "Base.groovy: unable to resolve class com.missing.Base",
            diagnostic.message,
        )
    }

    @Test
    fun `excluded sources are named by file in the same diagnostic, also from the cache`() {
        val excluded = listOf(ExcludedSource("/w/src/Broken.groovy", "unable to resolve class com.missing.Base"))

        val diagnostic = assertNotNull(mapper.mapFromCache(degraded(excluded), content)).diagnostics.single()

        assertTrue(
            diagnostic.message.endsWith(
                "Excluded from this compile because they fail to compile: Broken.groovy",
            ),
            diagnostic.message,
        )
    }

    @Test
    fun `excluding a broken file without losing types is not announced to the user`() {
        val excluded = listOf(ExcludedSource("/w/src/Broken.groovy", "boom"))
        val result = parsed().let { it.copy(outcome = it.outcome.copy(excludedSources = excluded)) }

        assertTrue(mapper.map(result, content).diagnostics.isEmpty())
    }
}
