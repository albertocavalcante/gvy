package com.github.albertocavalcante.groovylsp.engine.impl.native

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.engine.adapters.NativeParserAdapter
import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.CompletionProvider
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionProvider
import com.github.albertocavalcante.groovylsp.engine.api.DocumentSymbolProvider
import com.github.albertocavalcante.groovylsp.engine.api.FeatureSet
import com.github.albertocavalcante.groovylsp.engine.api.HoverProvider
import com.github.albertocavalcante.groovylsp.engine.api.LanguageEngine
import com.github.albertocavalcante.groovylsp.engine.api.LanguageSession
import com.github.albertocavalcante.groovylsp.engine.api.ParseResultMetadata
import com.github.albertocavalcante.groovylsp.engine.features.UnifiedCompletionProvider
import com.github.albertocavalcante.groovylsp.engine.features.UnifiedDefinitionProvider
import com.github.albertocavalcante.groovylsp.engine.features.UnifiedDocumentSymbolProvider
import com.github.albertocavalcante.groovylsp.engine.impl.native.features.NativeCompletionService
import com.github.albertocavalcante.groovylsp.engine.impl.native.features.NativeDefinitionService
import com.github.albertocavalcante.groovylsp.engine.impl.native.features.NativeHoverProvider
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import org.eclipse.lsp4j.Diagnostic
import java.net.URI

class NativeLanguageEngine(
    private val parserFacade: GroovyParserFacade,
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val sourceNavigator: SourceNavigator? = null,
) : LanguageEngine {
    override val id: String = "native"

    override fun createSession(request: ParseRequest): LanguageSession {
        val parseResult = parserFacade.parse(request)
        return createSession(parseResult, request.uri.toString(), request.content)
    }

    /**
     * Creates a session from an existing parse result.
     * Used by GroovyCompilationService to wrap cached results without re-parsing.
     */
    fun createSession(parseResult: ParseResult, uri: String, content: String): LanguageSession {
        // Wrap the result in an adapter
        val parseUnit = NativeParserAdapter(parseResult, uri)
        return NativeLanguageSession(
            parseUnit,
            parseResult,
            compilationService,
            documentProvider,
            sourceNavigator,
            content,
        )
    }

    override fun createSession(uri: URI, content: String): LanguageSession {
        val parseResult = parserFacade.parse(ParseRequest(uri, content))
        return createSession(parseResult, uri.toString(), content)
    }
}

class NativeLanguageSession(
    private val parseUnit: ParseUnit,
    private val parseResult: ParseResult,
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val sourceNavigator: SourceNavigator?,
    private val content: String,
) : LanguageSession {

    override val result: ParseResultMetadata = object : ParseResultMetadata {
        override val isSuccess: Boolean = parseUnit.isSuccessful
        override val diagnostics: List<Diagnostic> = parseUnit.diagnostics
    }

    override val features: FeatureSet by lazy {
        object : FeatureSet {
            override val hoverProvider: HoverProvider =
                NativeHoverProvider(parseResult, compilationService, documentProvider, sourceNavigator)
            override val documentSymbolProvider: DocumentSymbolProvider =
                UnifiedDocumentSymbolProvider(parseUnit)
            override val definitionProvider: DefinitionProvider =
                UnifiedDefinitionProvider(
                    parseUnit,
                    NativeDefinitionService(compilationService, sourceNavigator),
                )
            override val completionProvider: CompletionProvider =
                UnifiedCompletionProvider(
                    parseUnit,
                    NativeCompletionService(compilationService),
                    content,
                )
        }
    }
}
