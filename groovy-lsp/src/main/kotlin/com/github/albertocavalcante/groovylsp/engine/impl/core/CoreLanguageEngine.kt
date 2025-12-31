package com.github.albertocavalcante.groovylsp.engine.impl.core

import com.github.albertocavalcante.groovylsp.engine.adapters.CoreParserAdapter
import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.FeatureSet
import com.github.albertocavalcante.groovylsp.engine.api.HoverProvider
import com.github.albertocavalcante.groovylsp.engine.api.LanguageEngine
import com.github.albertocavalcante.groovylsp.engine.api.LanguageSession
import com.github.albertocavalcante.groovylsp.engine.api.ParseResultMetadata
import com.github.albertocavalcante.groovylsp.engine.features.UnifiedHoverProvider
import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.eclipse.lsp4j.Diagnostic

/**
 * Language Engine implementation backed by the core (JavaParser-based) parser.
 */
class CoreLanguageEngine : LanguageEngine {
    override val id: String = "core"

    // Create a parser configuration (could be customized via settings later)
    private val parserConfiguration = ParserConfiguration()
    private val parser = GroovyParser(parserConfiguration)

    override fun createSession(request: ParseRequest): LanguageSession {
        // Parse the code using the core parser
        val result = parser.parse(request.content)

        // Wrap the result in an adapter
        val parseUnit = CoreParserAdapter(result, request.uri.toString())

        // Return a session linked to this parse unit
        return CoreLanguageSession(parseUnit)
    }
}

/**
 * Language Session for the Core Engine.
 *
 * Uses the Unified ParseUnit abstraction to provide LSP features.
 */
class CoreLanguageSession(private val parseUnit: ParseUnit) : LanguageSession {

    override val result: ParseResultMetadata = object : ParseResultMetadata {
        override val isSuccess: Boolean = parseUnit.isSuccessful
        override val diagnostics: List<Diagnostic> = parseUnit.diagnostics
    }

    // Lazy initialization of features to avoid unnecessary object creation
    override val features: FeatureSet by lazy {
        object : FeatureSet {
            override val hoverProvider: HoverProvider = UnifiedHoverProvider(parseUnit)
        }
    }
}
