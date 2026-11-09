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
