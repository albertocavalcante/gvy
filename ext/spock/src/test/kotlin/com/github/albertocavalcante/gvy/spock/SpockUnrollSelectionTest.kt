package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.spock.UnrollPatternOrigin.FEATURE_ANNOTATION
import com.github.albertocavalcante.gvy.spock.UnrollPatternOrigin.FEATURE_NAME
import com.github.albertocavalcante.gvy.spock.UnrollPatternOrigin.SPEC_ANNOTATION
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Mirrors `UnrollExtension` precedence at spock-2.3, spock-2.4 and spock-2.5 (identical). */
class SpockUnrollSelectionTest {
    private val parser = GroovyParserFacade(SpockUnrollSelectionTest::class.java.classLoader)

    private fun select(source: String, feature: String = "feature"): UnrollSelection {
        val module: ModuleNode = requireNotNull(parser.parse(ParseRequest(URI.create("file:///S.groovy"), source)).ast)
        val method: MethodNode = module.classes.flatMap { it.methods }.single { it.name.startsWith(feature) }
        return SpockUnrollSelection.select(method, module)
    }

    private fun spec(classAnnotations: String = "", methodAnnotations: String = "", name: String = "feature #a") =
        """
        import spock.lang.*
        import spock.lang.Unroll
        import spock.lang.Rollup
        $classAnnotations
        class S extends Specification {
            $methodAnnotations
            def "$name"() {
                expect: a
                where: a << [1]
            }
        }
        """.trimIndent()

    @Test
    fun `feature annotation value wins and does not depend on configuration`() {
        val selection = assertIs<UnrollSelection.Pattern>(
            select(spec(classAnnotations = "@Unroll('spec #a')", methodAnnotations = "@Unroll('feature value #a')")),
        )
        assertEquals("feature value #a", selection.text)
        assertEquals(FEATURE_ANNOTATION, selection.origin)
        assertEquals(false, selection.requiresUnrollByDefault)
        assertNotNull(selection.annotationValue)
    }

    @Test
    fun `a feature name containing a hash beats the spec pattern`() {
        val selection = assertIs<UnrollSelection.Pattern>(select(spec(classAnnotations = "@Unroll('spec #a')")))
        assertEquals(FEATURE_NAME, selection.origin)
        assertEquals("feature #a", selection.text)
        assertEquals(false, selection.requiresUnrollByDefault)
        assertNull(selection.annotationValue)
    }

    @Test
    fun `an unannotated feature name pattern applies only when unrolling by default`() {
        val selection = assertIs<UnrollSelection.Pattern>(select(spec()))
        assertEquals(FEATURE_NAME, selection.origin)
        assertEquals(true, selection.requiresUnrollByDefault)
    }

    @Test
    fun `spec pattern applies when the feature has neither a value nor a hash in its name`() {
        val selection = assertIs<UnrollSelection.Pattern>(
            select(
                spec(classAnnotations = "@Unroll('spec #a')", methodAnnotations = "@Unroll", name = "feature"),
            ),
        )
        assertEquals(SPEC_ANNOTATION, selection.origin)
        assertEquals("spec #a", selection.text)
    }

    @Test
    fun `without any pattern the name depends on unknown configuration`() {
        assertSame(UnrollSelection.ConfigurationDependent, select(spec(name = "feature")))
        assertSame(
            UnrollSelection.ConfigurationDependent,
            select(spec(methodAnnotations = "@Unroll", name = "feature")),
        )
    }

    @Test
    fun `rollup disables unrolling at feature and spec level`() {
        assertSame(UnrollSelection.RolledUp, select(spec(methodAnnotations = "@Rollup")))
        assertSame(UnrollSelection.RolledUp, select(spec(classAnnotations = "@Rollup")))
        assertIs<UnrollSelection.Pattern>(select(spec(classAnnotations = "@Rollup", methodAnnotations = "@Unroll")))
    }

    @Test
    fun `conflicting annotations are uncertain because Spock rejects them`() {
        assertEquals(
            UnrollSelection.Uncertain(UnrollUncertainty.CONFLICTING_ANNOTATIONS),
            select(spec(methodAnnotations = "@Unroll @Rollup")),
        )
    }

    @Test
    fun `a conflicting parameterized sibling or in-module super spec rejects the whole spec`() {
        val sibling =
            """
            import spock.lang.Unroll
            import spock.lang.Rollup
            class S extends spock.lang.Specification {
                def "feature #a"() { expect: a
                    where: a << [1] }
                @Unroll @Rollup
                def other() { expect: b
                    where: b << [1] }
            }
            """.trimIndent()
        assertEquals(UnrollSelection.Uncertain(UnrollUncertainty.CONFLICTING_ANNOTATIONS), select(sibling))

        val inherited =
            """
            import spock.lang.Unroll
            import spock.lang.Rollup
            @Unroll @Rollup
            abstract class Base extends spock.lang.Specification {}
            class S extends Base {
                def "feature #a"() { expect: a
                    where: a << [1] }
            }
            """.trimIndent()
        assertEquals(UnrollSelection.Uncertain(UnrollUncertainty.CONFLICTING_ANNOTATIONS), select(inherited))
    }

    @Test
    fun `conflicts on non parameterized siblings are ignored like Spock does`() {
        val source =
            """
            import spock.lang.Unroll
            import spock.lang.Rollup
            class S extends spock.lang.Specification {
                def "feature #a"() { expect: a
                    where: a << [1] }
                @Unroll @Rollup
                def plain() { expect: true }
            }
            """.trimIndent()
        assertIs<UnrollSelection.Pattern>(select(source))
    }

    @Test
    fun `patterns expose the configuration they depend on`() {
        val annotated = assertIs<UnrollSelection.Pattern>(select(spec(methodAnnotations = "@Unroll('x #a')")))
        assertEquals(setOf(UnrollConfigurationKey.VALIDATE_EXPRESSIONS), annotated.dependsOn)

        val plain = assertIs<UnrollSelection.Pattern>(select(spec(methodAnnotations = "@Unroll('no placeholders')")))
        assertEquals(emptySet(), plain.dependsOn, "Expression validation is irrelevant without placeholders")

        val byDefault = assertIs<UnrollSelection.Pattern>(select(spec()))
        assertEquals(
            setOf(UnrollConfigurationKey.VALIDATE_EXPRESSIONS, UnrollConfigurationKey.UNROLL_BY_DEFAULT),
            byDefault.dependsOn,
        )
    }

    @Test
    fun `a module selector agrees with one-off selection for every feature`() {
        val source =
            """
            import spock.lang.Unroll
            class S extends spock.lang.Specification {
                @Unroll('one #a')
                def first() { expect: a
                    where: a << [1] }
                def "second #b"() { expect: b
                    where: b << [1] }
                def third() { expect: true }
            }
            """.trimIndent()
        val module = requireNotNull(parser.parse(ParseRequest(URI.create("file:///S.groovy"), source)).ast)
        val selector = SpockUnrollSelection.forModule(module)
        module.classes.flatMap { it.methods }.forEach { method ->
            assertEquals(SpockUnrollSelection.select(method, module), selector.select(method), method.name)
        }
    }

    @Test
    fun `a cached conflict is reused for every feature of specs sharing a super spec`() {
        val source =
            """
            import spock.lang.Unroll
            import spock.lang.Rollup
            @Unroll @Rollup
            abstract class Base extends spock.lang.Specification {}
            class One extends Base {
                def "one #a"() { expect: a
                    where: a << [1] }
                def "uno #a"() { expect: a
                    where: a << [1] }
            }
            class Two extends Base {
                def "two #b"() { expect: b
                    where: b << [1] }
            }
            """.trimIndent()
        val module = requireNotNull(parser.parse(ParseRequest(URI.create("file:///S.groovy"), source)).ast)
        val selector = SpockUnrollSelection.forModule(module)
        val features = module.classes.flatMap { it.methods }.filter { '#' in it.name }

        assertEquals(3, features.size)
        features.forEach { method ->
            assertEquals(UnrollSelection.Uncertain(UnrollUncertainty.CONFLICTING_ANNOTATIONS), selector.select(method))
        }
    }

    @Test
    fun `a nested spec's bare super spelling is not matched to a conflicting top level class`() {
        val source =
            """
            import spock.lang.Unroll
            import spock.lang.Rollup
            @Unroll @Rollup
            class Inner extends spock.lang.Specification {}
            class Outer {
                static class Inner extends spock.lang.Specification {}
                static class S extends Inner {
                    def "feature #a"() { expect: a
                        where: a << [1] }
                }
            }
            """.trimIndent()
        assertIs<UnrollSelection.Pattern>(select(source))
    }

    @Test
    fun `features without a where block are not parameterized`() {
        val source = "class S extends spock.lang.Specification {\n  def \"feature #a\"() { expect: true }\n}"
        assertSame(UnrollSelection.NotParameterized, select(source))
    }

    @Test
    fun `aliased annotations bind while star imported or foreign ones are handled conservatively`() {
        val alias = "import spock.lang.Unroll as U\nclass S extends spock.lang.Specification {\n" +
            "  @U('aliased #a')\n  def feature() { expect: a\n where: a << [1] }\n}"
        assertEquals("aliased #a", assertIs<UnrollSelection.Pattern>(select(alias)).text)

        val star = "import spock.lang.*\nclass S extends Specification {\n" +
            "  @Unroll('star #a')\n  def feature() { expect: a\n where: a << [1] }\n}"
        assertEquals(UnrollSelection.Uncertain(UnrollUncertainty.ANNOTATION_IDENTITY), select(star))

        val foreign = "import com.example.Unroll\nclass S extends spock.lang.Specification {\n" +
            "  @Unroll('foreign #a')\n  def feature() { expect: a\n where: a << [1] }\n}"
        assertSame(UnrollSelection.ConfigurationDependent, select(foreign))
    }

    @Test
    fun `a nested class shadowing an imported Unroll makes the annotation uncertain`() {
        val source =
            """
            import spock.lang.Unroll
            class S extends spock.lang.Specification {
                static class Unroll {}
                @Unroll('shadowed #a')
                def feature() { expect: a
                    where: a << [1] }
            }
            """.trimIndent()
        assertEquals(UnrollSelection.Uncertain(UnrollUncertainty.ANNOTATION_IDENTITY), select(source))
    }

    @Test
    fun `non constant annotation values are uncertain`() {
        assertEquals(
            UnrollSelection.Uncertain(UnrollUncertainty.NON_CONSTANT_VALUE),
            select(spec(methodAnnotations = "@Unroll('a ' + '#a')", name = "feature")),
        )
    }

    @Test
    fun `inherited features use their declaring spec annotations`() {
        val source =
            """
            import spock.lang.Unroll
            import spock.lang.Rollup
            @Unroll('base #a')
            abstract class Base extends spock.lang.Specification {
                def feature() {
                    expect: a
                    where: a << [1]
                }
            }
            @Rollup
            class Sub extends Base {}
            """.trimIndent()
        val selection = assertIs<UnrollSelection.Pattern>(select(source))
        assertEquals(SPEC_ANNOTATION, selection.origin)
        assertEquals("base #a", selection.text)
    }
}
