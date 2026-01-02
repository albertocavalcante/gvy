package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.StringLength
import java.net.URI

class ParserPropertiesTest {

    private val parser = GroovyParserFacade()

    @Property(tries = 100)
    fun parsingNeverThrowsUncheckedExceptions(@ForAll @StringLength(min = 0, max = 1000) content: String) {
        // Fuzzing the parser with random strings.
        // The parser should handle any input gracefully (producing diagnostics if invalid)
        // rather than crashing with NPE, IndexOutOfBounds, etc.
        try {
            parser.parse(
                ParseRequest(
                    uri = URI.create("file:///Fuzz.groovy"),
                    content = content,
                ),
            )
        } catch (e: Exception) {
            // Fail the test if an unexpected exception bubbles up.
            // The facade is expected to catch Groovy compilation errors and return them as diagnostics.
            throw AssertionError("Parser crashed on input: $content", e)
        }
    }
}
