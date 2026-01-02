package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.api.ParseUnit
import com.github.albertocavalcante.groovyparser.api.ParserCapabilities
import com.github.albertocavalcante.groovyparser.api.ParserProvider
import java.nio.file.Path

class CoreParserProvider(
    private val parser: GroovyParser = GroovyParser(ParserConfiguration().setAttributeComments(true)),
) : ParserProvider {

    override val name: String = "core"

    override val capabilities: ParserCapabilities = ParserCapabilities(
        supportsErrorRecovery = true,
        supportsCommentPreservation = true,
        supportsSymbolResolution = true,
        supportsRefactoring = false,
    )

    override fun parse(source: String, path: Path?): ParseUnit {
        val result = parser.parse(source)
        return CoreParseUnit(source, path, result)
    }
}
