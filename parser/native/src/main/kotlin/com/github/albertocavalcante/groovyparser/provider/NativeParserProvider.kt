package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseUnit
import com.github.albertocavalcante.groovyparser.api.ParserCapabilities
import com.github.albertocavalcante.groovyparser.api.ParserProvider
import java.nio.file.Path

class NativeParserProvider(private val facade: GroovyParserFacade = GroovyParserFacade()) : ParserProvider {

    override val name: String = "native"

    override val capabilities: ParserCapabilities = ParserCapabilities(
        supportsErrorRecovery = true,
        supportsCommentPreservation = false,
        supportsSymbolResolution = true,
        supportsRefactoring = false,
    )

    override fun parse(source: String, path: Path?): ParseUnit {
        val uri = path?.toUri() ?: java.net.URI.create("file:///unnamed.groovy")
        val request = ParseRequest(uri = uri, content = source)
        val result = facade.parse(request)
        return NativeParseUnit(source, path, result)
    }
}
