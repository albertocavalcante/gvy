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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpockVerdictMemoryTest {
    private val uri = "file:///src/TableSpec.groovy"
    private val baseText = "import spock.lang.*\nabstract class BaseSpec extends Specification {}\n"
    private val baseKey = Path.of("/src/BaseSpec.groovy").toAbsolutePath().normalize().toString()
    private val spec = "class TableSpec extends BaseSpec {}\n"
    private val read: (String) -> String? = { key -> baseText.takeIf { key == baseKey } }
    private val packageFiles: (String, String) -> String? = { key, _ ->
        "BaseSpec.groovy,TableSpec.groovy".takeIf { key == baseKey }
    }

    private fun parse(withBase: Boolean): ParseResult = GroovyParserFacade(javaClass.classLoader).parse(
        ParseRequest(
            uri = URI.create(uri),
            content = spec,
            openSources = if (withBase) mapOf(URI.create("file:///src/BaseSpec.groovy") to baseText) else emptyMap(),
            compilePhase = Phases.SEMANTIC_ANALYSIS,
        ),
    )

    private fun SpockVerdictMemory.exchange(result: ParseResult, reader: (String) -> String? = read): Set<String> {
        val module = checkNotNull(result.ast)
        val source = assertIs<SpockSourceContext.Available>(SpockSourceContext.from(result))
        return access(uri, "fp", result.outcome, reader, packageFiles).exchange(module, source)
    }

    @Test
    fun `a nested base prelude is matched by its enclosing class, not its short name`() {
        val outer1 = "class Outer1 { static abstract class Inner extends Specification {} }\n"
        val before = "import spock.lang.*\n" + outer1 +
            "class Outer2 { static abstract class Inner extends Specification {} }\n"
        val after = "import spock.lang.*\n" + outer1 + "class Outer2 { static abstract class Inner { void m( } }\n"

        val target = assertNotNull(SpockVerdictMemory.preludeOf(before, "Outer2\$Inner"))
        val targetAfter = assertNotNull(SpockVerdictMemory.preludeOf(after, "Outer2\$Inner"))
        assertEquals("Specification", target.extendsName)
        assertNotEquals(target.hash, targetAfter.hash)
        val sibling = assertNotNull(SpockVerdictMemory.preludeOf(before, "Outer1\$Inner"))
        val siblingAfter = assertNotNull(SpockVerdictMemory.preludeOf(after, "Outer1\$Inner"))
        assertEquals(sibling.hash, siblingAfter.hash)
    }

    @Test
    fun `an unreadable base at recall denies reuse`() {
        val memory = SpockVerdictMemory()
        memory.exchange(parse(withBase = true))

        assertEquals(emptySet(), memory.exchange(parse(withBase = false)) { null })
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
        val inFlight = memory.access(uri, "fp", clean.outcome, read, packageFiles)

        memory.forget(uri)
        inFlight.exchange(checkNotNull(clean.ast), SpockSourceContext.from(checkNotNull(clean.ast), isPartial = false))

        assertEquals(emptySet(), memory.exchange(parse(withBase = false)))
    }
}
