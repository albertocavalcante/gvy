package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpockUnrollSourceTest {
    private val parser = GroovyParserFacade(SpockUnrollSourceTest::class.java.classLoader)

    private fun feature(source: String): MethodNode =
        requireNotNull(parser.parse(ParseRequest(URI.create("file:///S.groovy"), source)).ast)
            .classes.flatMap { it.methods }.single { it.name != "<init>" && !it.isSynthetic }

    private fun wrap(header: String) = "class S extends spock.lang.Specification {\n    $header {\n" +
        "        expect: x\n        where: x << [1]\n    }\n}\n"

    /** Source text covered by each placeholder after mapping its runtime range. */
    private fun mappedPlaceholders(source: String): List<String> {
        val method = feature(source)
        val literal = assertNotNull(SpockUnrollSource.featureName(source, method), "name literal must map")
        return SpockUnrollPattern.parse(method.name).map { source.substring(literal.sourceRange(it.range)) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("spellings")
    fun `placeholder ranges map back to their source spelling`(header: String, expected: List<String>) {
        assertEquals(expected, mappedPlaceholders(wrap(header)))
    }

    @Test
    fun `triple quoted names survive CRLF and line continuations`() {
        val source = wrap("def \"\"\"first #x\n    then #x.size() \\\ntail #x\"\"\"()").replace("\n", "\r\n")
        assertEquals(listOf("#x", "#x.size()", "#x"), mappedPlaceholders(source))
    }

    @Test
    fun `supplementary characters before and inside the literal keep UTF-16 offsets`() {
        assertEquals(listOf("#x"), mappedPlaceholders(wrap("/* \uD83D\uDE00 */ def \"\uD83D\uDE00 #x\"()")))
    }

    @Test
    fun `root ranges and tail ranges map independently`() {
        val source = wrap("def \"a \\u0023x.size()\"()")
        val method = feature(source)
        val literal = assertNotNull(SpockUnrollSource.featureName(source, method))
        val placeholder = SpockUnrollPattern.parse(method.name).single()

        assertEquals("x", source.substring(literal.sourceRange(placeholder.rootRange)))
        assertEquals("size()", source.substring(literal.sourceRange(placeholder.tail.single().range)))
    }

    @Test
    fun `annotation values map through their constant expression position`() {
        val source = "class S extends spock.lang.Specification {\n" +
            "    @spock.lang.Unroll(\"value \\t #x\")\n    def feature() {\n" +
            "        expect: x\n        where: x << [1]\n    }\n}\n"
        val method = feature(source)
        val value = method.annotations.single().getMember("value") as ConstantExpression
        val literal = assertNotNull(SpockUnrollSource.annotationValue(source, value))

        val placeholder = SpockUnrollPattern.parse(value.value as String).single()
        assertEquals("#x", source.substring(literal.sourceRange(placeholder.range)))
    }

    @Test
    fun `identifier names and mismatched literals are unmapped rather than guessed`() {
        val identifier = wrap("def feature()")
        assertNull(SpockUnrollSource.featureName(identifier, feature(identifier)))

        val source = wrap("def \"a #x\"()")
        val stale = source.replace("a #x", "b #y")
        assertNull(SpockUnrollSource.featureName(stale, feature(source)), "Source must reproduce the AST value")

        val misplaced = ConstantExpression("#x").apply {
            lineNumber = 2
            columnNumber = 5
            lastLineNumber = 2
            lastColumnNumber = 9
        }
        assertNull(SpockUnrollSource.annotationValue(source, misplaced))
    }

    companion object {
        @JvmStatic
        fun spellings(): List<Arguments> = listOf(
            Arguments.of("def \"plain #x and #x.y\"()", listOf("#x", "#x.y")),
            Arguments.of("def 'single #x'()", listOf("#x")),
            Arguments.of("def \"escaped \\\" \\\$ \\\\ \\t #x\"()", listOf("#x")),
            Arguments.of("def 'unicode \\u00e9 #x'()", listOf("#x")),
            Arguments.of("def 'octal \\101\\7 #x'()", listOf("#x")),
            Arguments.of("def 'escaped hash \\u0023x'()", listOf("\\u0023x")),
            Arguments.of("def '''triple #x'''()", listOf("#x")),
            Arguments.of("def /slashy \\/ #x/()", listOf("#x")),
            Arguments.of("@spock.lang.Unroll def \"annotated #x\"()", listOf("#x")),
        )
    }
}
