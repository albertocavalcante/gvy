package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.EXPLICIT_IMPORT
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.INHERITED_NESTED_SCOPE
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.MODULE_BASE
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.MODULE_HIERARCHY_CYCLE
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.NO_SUPERCLASS
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.QUALIFIED_SUPERCLASS
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.SCRIPT_CLASS
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.SHADOWED_NAME
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.STAR_IMPORT_ONLY
import com.github.albertocavalcante.gvy.spock.SpecificationEvidence.UNRESOLVED_SUPERCLASS
import com.github.albertocavalcante.gvy.spock.SpecificationStatus.NOT_SPECIFICATION
import com.github.albertocavalcante.gvy.spock.SpecificationStatus.SPECIFICATION
import com.github.albertocavalcante.gvy.spock.SpecificationStatus.UNCERTAIN
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpockSpecificationContextTest {

    /** Hides Spock so source-binding results cannot depend on the test classpath. */
    private val withoutSpock = object : ClassLoader(SpockSpecificationContextTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("spock.")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }

        override fun getResource(name: String) = if (name.startsWith("spock/")) null else super.getResource(name)
    }
    private val loaders = listOf(SpockSpecificationContextTest::class.java.classLoader, withoutSpock)

    private fun contexts(source: String): List<SpockSourceContext> = loaders.map { loader ->
        val result = GroovyParserFacade(loader).parse(ParseRequest(URI.create("file:///src/Probe.groovy"), source))
        SpockSourceContext.from(result)
    }

    /** Asserts the same classification with and without Spock on the parser classpath. */
    private fun classify(source: String): Map<String, Pair<SpecificationStatus, SpecificationEvidence>> {
        val results = contexts(source).map { context ->
            assertIs<SpockSourceContext.Available>(context).classes.associate {
                it.classNode.name to (it.status to it.evidence)
            }
        }
        assertEquals(results.first(), results.last(), "Source context must not depend on classpath")
        return results.first()
    }

    @Test
    fun `qualified superclass is a specification`() {
        val result = classify("class A extends spock.lang.Specification {}")
        assertEquals(SPECIFICATION to QUALIFIED_SUPERCLASS, result["A"])
    }

    @Test
    fun `explicit import binds the simple name`() {
        val result = classify("import spock.lang.Specification\nclass A extends Specification {}")
        assertEquals(SPECIFICATION to EXPLICIT_IMPORT, result["A"])
    }

    @Test
    fun `aliased import binds the alias`() {
        val result = classify("import spock.lang.Specification as Spec\nclass A extends Spec {}")
        assertEquals(SPECIFICATION to EXPLICIT_IMPORT, result["A"])
    }

    @Test
    fun `alias does not leave the original simple name bound`() {
        val result = classify("import spock.lang.Specification as Spec\nclass A extends Specification {}")
        assertEquals(UNCERTAIN to UNRESOLVED_SUPERCLASS, result["A"])
    }

    @Test
    fun `star import alone remains uncertain because same-package classes take precedence`() {
        val result = classify("import spock.lang.*\nclass A extends Specification {}")
        assertEquals(UNCERTAIN to STAR_IMPORT_ONLY, result["A"])
    }

    @Test
    fun `explicit non-Spock import wins over a Spock star import`() {
        val result = classify("import spock.lang.*\nimport com.example.Specification\nclass A extends Specification {}")
        assertEquals(UNCERTAIN to UNRESOLVED_SUPERCLASS, result["A"])
    }

    @Test
    fun `other qualified Specification is not treated as Spock`() {
        val result = classify("import spock.lang.*\nclass A extends com.example.Specification {}")
        assertEquals(UNCERTAIN to UNRESOLVED_SUPERCLASS, result["A"])
    }

    @Test
    fun `external custom base remains uncertain`() {
        val result = classify("import spock.lang.Specification\nclass A extends BaseSpec {}")
        assertEquals(UNCERTAIN to UNRESOLVED_SUPERCLASS, result["A"])
    }

    @Test
    fun `module base classes propagate their classification`() {
        val result = classify(
            """
            package com.example
            import spock.lang.Specification
            abstract class BaseSpec extends Specification {}
            class A extends BaseSpec {}
            class B extends com.example.BaseSpec {}
            class Plain {}
            class C extends Plain {}
            class D extends External {}
            class E extends D {}
            """.trimIndent(),
        )
        assertEquals(SPECIFICATION to EXPLICIT_IMPORT, result["com.example.BaseSpec"])
        assertEquals(SPECIFICATION to MODULE_BASE, result["com.example.A"])
        assertEquals(SPECIFICATION to MODULE_BASE, result["com.example.B"])
        assertEquals(NOT_SPECIFICATION to MODULE_BASE, result["com.example.C"])
        assertEquals(UNCERTAIN to UNRESOLVED_SUPERCLASS, result["com.example.D"])
        assertEquals(UNCERTAIN to MODULE_BASE, result["com.example.E"])
    }

    @Test
    fun `a nested class and an import alias binding the same name stay uncertain`() {
        val result = classify(
            """
            import spock.lang.Specification as Spec
            class Outer {
                static class Spec {}
                static class A extends Spec {}
            }
            """.trimIndent(),
        )
        assertEquals(UNCERTAIN to SHADOWED_NAME, result["Outer\$A"])
    }

    @Test
    fun `nested classes resolve bare names against sibling bases in the enclosing class`() {
        val result = classify(
            """
            class Outer {
                static class Base extends spock.lang.Specification {}
                static class A extends Base {}
            }
            class Other extends Base {}
            """.trimIndent(),
        )
        assertEquals(SPECIFICATION to MODULE_BASE, result["Outer\$A"])
        assertEquals(UNCERTAIN to UNRESOLVED_SUPERCLASS, result["Other"], "Out-of-scope nested classes do not bind")
    }

    @Test
    fun `duplicate class names keep separate results`() {
        val source = "class A {}\nclass A extends spock.lang.Specification {}"
        val context = assertIs<SpockSourceContext.Available>(contexts(source).first())

        assertEquals(2, context.classes.size)
        assertTrue(context.classes[0].classNode !== context.classes[1].classNode)
        assertEquals(NOT_SPECIFICATION to NO_SUPERCLASS, context.classes[0].let { it.status to it.evidence })
        assertEquals(SPECIFICATION to QUALIFIED_SUPERCLASS, context.classes[1].let { it.status to it.evidence })
    }

    @Test
    fun `declared classes own their bodies in files that also contain script statements`() {
        val source =
            """
            println 'before'
            class FooSpec extends spock.lang.Specification {
                def "feature"() {
                    expect: true
                }
            }
            println 'after'
            """.trimIndent()
        val context = assertIs<SpockSourceContext.Available>(contexts(source).first())

        assertEquals("FooSpec", context.ownerAt(3, 12)?.classNode?.name)
        assertEquals(SPECIFICATION, context.ownerAt(3, 12)?.status)
        assertTrue(context.ownerAt(6, 2)?.classNode?.isScript != false, "Trailing script statements are not FooSpec")
    }

    @Test
    fun `bare names inside a class whose enclosing class extends another stay uncertain`() {
        val result = classify(
            """
            class Base extends spock.lang.Specification {}
            class Parent { static class Base {} }
            class Outer extends Parent {
                static class S extends Base {}
            }
            """.trimIndent(),
        )
        assertEquals(SPECIFICATION to QUALIFIED_SUPERCLASS, result["Base"])
        assertEquals(UNCERTAIN to INHERITED_NESTED_SCOPE, result["Outer\$S"], "Parent.Base may bind first")
    }

    @Test
    fun `dotted names starting with a bare segment stay uncertain in inherited scopes`() {
        val result = classify(
            """
            class Base { static class Inner extends spock.lang.Specification {} }
            class Parent { static class Base { static class Inner {} } }
            class Outer extends Parent {
                static class S extends Base.Inner {}
                static class Q extends spock.lang.Specification {}
            }
            """.trimIndent(),
        )
        assertEquals(UNCERTAIN to INHERITED_NESTED_SCOPE, result["Outer\$S"], "Parent.Base.Inner may bind first")
        assertEquals(SPECIFICATION to QUALIFIED_SUPERCLASS, result["Outer\$Q"])
    }

    @Test
    fun `qualified nested superclass spellings resolve to the module class`() {
        val result = classify(
            """
            class Outer {
                static class Base extends spock.lang.Specification {}
            }
            class A extends Outer.Base {}
            """.trimIndent(),
        )
        assertEquals(SPECIFICATION to MODULE_BASE, result["A"])
    }

    @Test
    fun `a subclass of a duplicated class name stays uncertain`() {
        val source = "class A {}\nclass A extends spock.lang.Specification {}\nclass B extends A {}"
        val context = assertIs<SpockSourceContext.Available>(contexts(source).first())

        val b = context.classes.single { it.classNode.name == "B" }
        assertEquals(UNCERTAIN to SHADOWED_NAME, b.pair())
    }

    @Test
    fun `hierarchy cycles are uncertain rather than looping`() {
        val result = classify("class A extends B {}\nclass B extends A {}")
        assertEquals(UNCERTAIN to MODULE_HIERARCHY_CYCLE, result["A"])
        assertEquals(UNCERTAIN to MODULE_HIERARCHY_CYCLE, result["B"])
    }

    @Test
    fun `ordinary classes with block-like labels are not specifications`() {
        val result = classify(
            """
            import spock.lang.Specification
            class Helper {
                def run() {
                    given: 1
                    when: 2
                    then: 3
                }
            }
            """.trimIndent(),
        )
        assertEquals(NOT_SPECIFICATION to NO_SUPERCLASS, result["Helper"])
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "class A { String s = 'class X extends spock.lang.Specification {}' }",
            "class A { /* class X extends spock.lang.Specification {} */ }",
            "// import spock.lang.Specification\nclass A extends Specification {}",
            "class A { String s = \"import spock.lang.Specification\" }",
        ],
    )
    fun `comments and string literals cannot establish identity`(source: String) {
        val result = classify(source)
        assertTrue(result.values.none { it.first == SPECIFICATION }, result.toString())
    }

    @Test
    fun `scripts are not specifications even when they import Spock`() {
        val result = classify("import spock.lang.Specification\nprintln 'hi'")
        assertTrue(result.isNotEmpty())
        assertTrue(result.values.all { it == NOT_SPECIFICATION to SCRIPT_CLASS }, result.toString())
    }

    @Test
    fun `multiple specifications and ordinary classes keep separate ownership`() {
        val source =
            """
            import spock.lang.Specification
            class FirstSpec extends Specification {
                def "one"() {
                    expect: true
                }
            }
            class Helper {
                def help() { 1 }
            }
            class SecondSpec extends Specification {
                def "two"() {
                    expect: true
                    def anonymous = new Object() {
                        def inner() { 2 }
                    }
                }
            }
            """.trimIndent()
        val context = assertIs<SpockSourceContext.Available>(contexts(source).first())

        assertEquals("FirstSpec", context.ownerAt(3, 8)?.classNode?.name)
        assertEquals(SPECIFICATION, context.ownerAt(3, 8)?.status)
        assertEquals("Helper", context.ownerAt(7, 10)?.classNode?.name)
        assertEquals(NOT_SPECIFICATION, context.ownerAt(7, 10)?.status)
        assertEquals("SecondSpec", context.ownerAt(11, 8)?.classNode?.name)
        val anonymous = context.ownerAt(13, 20)
        assertEquals("SecondSpec", anonymous?.classNode?.outerClass?.name, "Anonymous classes own their bodies")
        // Pre-resolution `Object` is a bare name; SecondSpec's superclass could supply a nested `Object`.
        assertEquals(UNCERTAIN to INHERITED_NESTED_SCOPE, anonymous?.let { it.status to it.evidence })
        assertEquals("SecondSpec", context.declaredOwnerAt(13, 20)?.classNode?.name, "Declared owner skips anonymous")
        assertEquals(SPECIFICATION, context.declaredOwnerAt(13, 20)?.status)
        assertEquals("FirstSpec", context.declaredOwnerAt(3, 8)?.classNode?.name)
        assertNull(context.declaredOwnerAt(0, 0))
        assertNull(context.ownerAt(0, 0), "Imports are outside every class")
        assertNull(context.ownerAt(5, 1), "The column after a closing brace is outside the class")
    }

    @Test
    fun `missing AST is unavailable rather than absent`() {
        val source = "import spock.lang.Specification\nclass A extends Specification { def f() { expect: "
        contexts(source).forEach { context ->
            assertSame(SpockSourceContext.Unavailable, context)
        }
    }

    @Test
    fun `resolved hierarchies use the compile context that produced them`() {
        val module = ModuleNode(null as org.codehaus.groovy.control.SourceUnit?)
        val spec = ClassNode("A", Modifier.PUBLIC, ClassHelper.make(spock.lang.Specification::class.java))
        val plain = ClassNode("B", Modifier.PUBLIC, ClassHelper.make(java.util.ArrayList::class.java))
        module.addClass(spec)
        module.addClass(plain)

        val result = SpockSourceContext.from(module, isPartial = false).classes.associateBy { it.classNode.name }

        assertEquals(SPECIFICATION to SpecificationEvidence.RESOLVED_HIERARCHY, result.getValue("A").pair())
        assertEquals(NOT_SPECIFICATION to SpecificationEvidence.RESOLVED_HIERARCHY, result.getValue("B").pair())
    }

    /**
     * With Spock on the classpath the parse must really reach SEMANTIC_ANALYSIS, every expected class
     * must be present and each result must be exact. Without Spock each present class is the truth or
     * UNCERTAIN, never confidently wrong.
     */
    @ParameterizedTest
    @MethodSource("resolvedTruth")
    fun `resolving with or without Spock is exact or uncertain, never confidently wrong`(
        source: String,
        resolved: Map<String, Pair<SpecificationStatus, SpecificationEvidence>>,
    ) {
        val request = ParseRequest(
            URI.create("file:///src/Probe.groovy"),
            source,
            compilePhase = Phases.SEMANTIC_ANALYSIS,
        )
        val withSpock = GroovyParserFacade(loaders.first()).parse(request)
        assertTrue(!withSpock.outcome.isDegraded, "No fallback expected: ${withSpock.outcome}")
        assertTrue(withSpock.outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS, "${withSpock.outcome}")
        val exact = assertIs<SpockSourceContext.Available>(SpockSourceContext.from(withSpock))
            .classes.associate { it.classNode.name to it.pair() }
        resolved.forEach { (name, expected) -> assertEquals(expected, exact[name], name) }

        val withoutSpock = SpockSourceContext.from(GroovyParserFacade(withoutSpock).parse(request))
        val classes = (withoutSpock as? SpockSourceContext.Available)?.classes.orEmpty()
        resolved.forEach { (name, expected) ->
            classes.filter { it.classNode.name == name }.forEach { actual ->
                assertTrue(actual.status == expected.first || actual.status == UNCERTAIN, "$name: $actual")
            }
        }
    }

    private fun SpockClassContext.pair() = status to evidence

    companion object {
        private val resolvedSpec = SPECIFICATION to SpecificationEvidence.RESOLVED_HIERARCHY
        private val resolvedOther = NOT_SPECIFICATION to SpecificationEvidence.RESOLVED_HIERARCHY

        @JvmStatic
        fun resolvedTruth(): List<Arguments> = listOf(
            Arguments.of("class A extends spock.lang.Specification {}", mapOf("A" to resolvedSpec)),
            Arguments.of(
                "import spock.lang.Specification\nclass A extends Specification {}",
                mapOf("A" to resolvedSpec),
            ),
            Arguments.of("import spock.lang.Specification as S\nclass A extends S {}", mapOf("A" to resolvedSpec)),
            Arguments.of("import spock.lang.*\nclass A extends Specification {}", mapOf("A" to resolvedSpec)),
            Arguments.of(
                "import spock.lang.*\nimport java.util.ArrayList as Specification\nclass A extends Specification {}",
                mapOf("A" to resolvedOther),
            ),
            Arguments.of("class A extends java.util.ArrayList { def f() { given: 1 } }", mapOf("A" to resolvedOther)),
            Arguments.of(
                "class Base extends spock.lang.Specification {}\nclass A extends Base {}",
                mapOf("Base" to resolvedSpec, "A" to (SPECIFICATION to MODULE_BASE)),
            ),
            Arguments.of(
                "class Outer {\n static class Base extends spock.lang.Specification {}\n" +
                    " static class A extends Base {}\n}",
                mapOf(
                    "Outer" to (NOT_SPECIFICATION to NO_SUPERCLASS),
                    "Outer\$Base" to resolvedSpec,
                    // The compiler bound the superclass to the module's primary `Outer$Base` node.
                    "Outer\$A" to (SPECIFICATION to MODULE_BASE),
                ),
            ),
            Arguments.of(
                "class Base extends spock.lang.Specification {}\nclass Parent {}\n" +
                    "class Outer extends Parent {\n static class S extends Base {}\n}",
                // Compiler-bound bases stay confident even where the source rule would be uncertain.
                mapOf("Outer\$S" to (SPECIFICATION to MODULE_BASE)),
            ),
        )
    }
}
