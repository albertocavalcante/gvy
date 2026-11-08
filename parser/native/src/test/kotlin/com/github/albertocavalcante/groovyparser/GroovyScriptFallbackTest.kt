package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GroovyScriptFallbackTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("intentionalScripts")
    fun `intentional script text cannot invent a class declaration`(name: String, source: String) {
        val result = GroovyParserFacade().parse(
            ParseRequest(URI.create("file:///ScriptCases.groovy"), source, compilePhase = Phases.CANONICALIZATION),
        )

        assertNotNull(result.ast, name)
        assertEquals(Phases.CANONICALIZATION, result.outcome.requestedPhase, name)
        assertEquals(Phases.CANONICALIZATION, result.outcome.achievedPhase, name)
        assertNull(result.outcome.fallback, name)
        assertFalse(result.outcome.typesUnresolved, name)
    }

    companion object {
        @JvmStatic
        fun intentionalScripts(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "class literal followed by a new statement",
                "import java.util.Map\ndef first = java.util.Map.Entry.class\ndef second = Map.Entry.class\n",
            ),
            Arguments.of("line comment", "// class Pretend\nprintln 1\n"),
            Arguments.of("safe class property", "def first = 1?.class\nprintln first\n"),
            Arguments.of("spread class property", "def first = [1]*.class\nprintln first\n"),
            Arguments.of("direct class field", "def first = new Object().@class\nprintln first\n"),
            Arguments.of("block comment", "/* class Pretend */\nprintln 1\n"),
            Arguments.of("single quoted string", "println 'class Pretend'\n"),
            Arguments.of("double quoted string", "println \"class Pretend\"\n"),
            Arguments.of("slashy string", "println(/class Pretend/)\n"),
            Arguments.of("dollar slashy string", "println($/class Pretend/$)\n"),
            Arguments.of("interpolated string", "def suffix = 'ok'\nprintln \"class Pretend ${'$'}suffix\"\n"),
        )
    }
}
