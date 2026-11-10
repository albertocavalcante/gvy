package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Bases declared in other files of the same compile, as the workspace compile delivers them. */
class SpockCompiledHierarchyTest {
    private fun verdict(
        source: String,
        others: Map<String, String> = emptyMap(),
        name: String,
        loader: ClassLoader = SpockCompiledHierarchyTest::class.java.classLoader,
    ): SpockClassContext {
        val request = ParseRequest(
            uri = URI.create("file:///src/Probe.groovy"),
            content = source,
            openSources = others.mapKeys { (file, _) -> URI.create("file:///src/$file") },
            compilePhase = Phases.SEMANTIC_ANALYSIS,
        )
        val result = GroovyParserFacade(loader).parse(request)
        val context = assertIs<SpockSourceContext.Available>(SpockSourceContext.from(result))
        return context.classes.single { it.classNode.name == name }
    }

    private val baseSpec = mapOf(
        "BaseSpec.groovy" to "import spock.lang.Specification\nclass BaseSpec extends Specification {}",
    )

    @Test
    fun `a base spec in another file of the same compile is a specification`() {
        val foo = verdict("class FooSpec extends BaseSpec {}", baseSpec, "FooSpec")
        assertEquals(SpecificationStatus.SPECIFICATION, foo.status)
        assertEquals("COMPILED_SOURCE_HIERARCHY", foo.evidence.name)
    }

    @Test
    fun `a multi-file chain to Specification is a specification`() {
        val others = baseSpec + ("MidSpec.groovy" to "class MidSpec extends BaseSpec {}")
        val foo = verdict("class FooSpec extends MidSpec {}", others, "FooSpec")
        assertEquals(SpecificationStatus.SPECIFICATION, foo.status)
        assertEquals("COMPILED_SOURCE_HIERARCHY", foo.evidence.name)
    }

    @Test
    fun `an ordinary base in another file is not a specification`() {
        val rule = verdict("class Rule extends Base {}", mapOf("Base.groovy" to "class Base {}"), "Rule")
        assertEquals(SpecificationStatus.NOT_SPECIFICATION, rule.status)
        assertEquals("COMPILED_SOURCE_HIERARCHY", rule.evidence.name)
    }

    @Test
    fun `a base that is not part of the compile stays uncertain`() {
        // The parser compiles an empty stand-in for the missing BaseSpec; a stub is not evidence.
        val foo = verdict("class FooSpec extends BaseSpec {}", name = "FooSpec")
        assertEquals(SpecificationStatus.UNCERTAIN, foo.status)
    }

    @Test
    fun `an unresolved link anywhere in the chain stays uncertain`() {
        val others = mapOf("BaseSpec.groovy" to "class BaseSpec extends MissingBase {}")
        val foo = verdict("class FooSpec extends BaseSpec {}", others, "FooSpec")
        assertEquals(SpecificationStatus.UNCERTAIN, foo.status)
    }

    @Test
    fun `a stubbed Specification without Spock on the classpath stays uncertain`() {
        val withoutSpock = object : ClassLoader(SpockCompiledHierarchyTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("spock.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }

            override fun getResource(name: String) = if (name.startsWith("spock/")) null else super.getResource(name)
        }
        val foo = verdict("class FooSpec extends BaseSpec {}", baseSpec, "FooSpec", withoutSpock)
        assertEquals(SpecificationStatus.UNCERTAIN, foo.status)
    }

    @Disabled("hangs in parser: #1369")
    @Test
    fun `a cycle across files terminates as uncertain`() {
        val others = mapOf("A.groovy" to "class A extends B {}", "B.groovy" to "class B extends Probe {}")
        assertEquals(SpecificationStatus.UNCERTAIN, verdict("class Probe extends A {}", others, "Probe").status)
    }
}
