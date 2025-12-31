package com.github.albertocavalcante.groovylsp.engine.impl.native

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.compilation.toLspDiagnostic
import com.github.albertocavalcante.groovylsp.engine.api.FeatureSet
import com.github.albertocavalcante.groovylsp.engine.api.HoverProvider
import com.github.albertocavalcante.groovylsp.engine.api.LanguageEngine
import com.github.albertocavalcante.groovylsp.engine.api.LanguageSession
import com.github.albertocavalcante.groovylsp.engine.api.ParseResultMetadata
import com.github.albertocavalcante.groovylsp.engine.impl.native.features.NativeHoverProvider
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import org.eclipse.lsp4j.Diagnostic

class NativeLanguageEngine(
    private val parserFacade: GroovyParserFacade,
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val sourceNavigator: SourceNavigator? = null,
) : LanguageEngine {
    override val id: String = "native"

    override fun createSession(request: ParseRequest): LanguageSession {
        val parseResult = parserFacade.parse(request)
        return createSession(parseResult)
    }

    /**
     * Creates a session from an existing parse result.
     * Used by GroovyCompilationService to wrap cached results without re-parsing.
     */
    fun createSession(parseResult: ParseResult): LanguageSession =
        NativeLanguageSession(parseResult, compilationService, documentProvider, sourceNavigator)
}

class NativeLanguageSession(
    // We hold the full result here, opaque to the outside
    val parseResult: ParseResult,
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val sourceNavigator: SourceNavigator?,
) : LanguageSession {

    override val result: ParseResultMetadata = object : ParseResultMetadata {
        override val isSuccess: Boolean = parseResult.isSuccessful
        override val diagnostics: List<Diagnostic> = parseResult.diagnostics.map {
            it.toLspDiagnostic()
        }
    }

    override val features: FeatureSet by lazy {
        object : FeatureSet {
            override val hoverProvider: HoverProvider =
                NativeHoverProvider(parseResult, compilationService, documentProvider, sourceNavigator)
        }
    }
}
