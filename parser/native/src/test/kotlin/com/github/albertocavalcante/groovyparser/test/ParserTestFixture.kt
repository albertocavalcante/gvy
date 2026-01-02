package com.github.albertocavalcante.groovyparser.test

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import java.net.URI

class ParserTestFixture {

    private val parser = GroovyParserFacade()

    fun parse(code: String, uri: String = "file:///Test.groovy"): ParseResult = parser.parse(
        ParseRequest(
            uri = URI.create(uri),
            content = code,
        ),
    )

    fun parseResource(path: String): ParseResult {
        val resource = javaClass.getResource(path)
            ?: throw IllegalArgumentException("Resource not found: $path")
        return parse(resource.readText(), "file://$path")
    }
}
