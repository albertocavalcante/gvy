// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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
        // The public ParseUnit.typeAt adapter is not wired to the core symbol resolver.
        supportsSymbolResolution = false,
        supportsRefactoring = false,
    )

    override fun parse(source: String, path: Path?): ParseUnit {
        val result = parser.parse(source)
        return CoreParseUnit(source, path, result)
    }
}
