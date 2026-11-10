package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpockVerdictMemoryTest {
    private val uri = "file:///src/TableSpec.groovy"
    private val baseText = "import spock.lang.*\nabstract class BaseSpec extends Specification {}\n"
    private val baseKey = Path.of("/src/BaseSpec.groovy").toAbsolutePath().normalize().toString()
    private val spec = "class TableSpec extends BaseSpec {}\n"
    private val read: (String) -> String? = { key -> baseText.takeIf { key == baseKey } }

    private fun parse(withBase: Boolean): ParseResult = GroovyParserFacade(javaClass.classLoader).parse(
        ParseRequest(
            uri = URI.create(uri),
            content = spec,
            openSources = if (withBase) mapOf(URI.create("file:///src/BaseSpec.groovy") to baseText) else emptyMap(),
            compilePhase = Phases.SEMANTIC_ANALYSIS,
        ),
    )

    private fun SpockVerdictMemory.exchange(result: ParseResult): Set<String> {
        val module = checkNotNull(result.ast)
        val source = assertIs<SpockSourceContext.Available>(SpockSourceContext.from(result))
        return access(uri, "fp", result.outcome, read).exchange(module, source)
    }

    @Test
    fun `a stubbed base recalls the clean verdict`() {
        val memory = SpockVerdictMemory()
        val degraded = parse(withBase = false)
        assertTrue(degraded.outcome.stubbedClasses.contains("BaseSpec"), "precondition: ${degraded.outcome}")

        assertEquals(emptySet(), memory.exchange(parse(withBase = true)))
        assertEquals(setOf("TableSpec"), memory.exchange(degraded))
    }

    @Test
    fun `a record written by a request that started before close is discarded`() {
        val memory = SpockVerdictMemory()
        val clean = parse(withBase = true)
        val inFlight = memory.access(uri, "fp", clean.outcome, read)

        memory.forget(uri)
        inFlight.exchange(checkNotNull(clean.ast), SpockSourceContext.from(checkNotNull(clean.ast), isPartial = false))

        assertEquals(emptySet(), memory.exchange(parse(withBase = false)))
    }
}
