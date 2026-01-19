package com.github.albertocavalcante.gvy.gls.engine

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.engine.api.LanguageEngine
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.engine.impl.core.CoreLanguageEngine
import com.github.albertocavalcante.gvy.gls.engine.impl.native.NativeLanguageEngine
import com.github.albertocavalcante.gvy.gls.engine.impl.rewrite.OpenRewriteLanguageEngine
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator

/**
 * Factory for creating language engines based on configuration.
 *
 * Uses the [EngineType] sealed interface to ensure exhaustive handling of all engine types.
 * When a new engine type is added, the compiler will enforce updating this factory.
 */
object EngineFactory {

    /**
     * Creates a [LanguageEngine] based on the provided configuration.
     *
     * @param config Engine configuration specifying type and features
     * @param parser The Groovy parser facade for parsing source code
     * @param compilationService The compilation service for coordination
     * @param documentProvider Provider for document content access
     * @param sourceNavigator Optional navigator for source code navigation
     * @return A configured [LanguageEngine] instance
     */
    fun create(
        config: EngineConfiguration,
        parser: GroovyParserFacade,
        compilationService: GroovyCompilationService,
        documentProvider: DocumentProvider,
        sourceNavigator: SourceNavigator?,
    ): LanguageEngine = when (config.type) {
        EngineType.Native -> NativeLanguageEngine(
            parserFacade = parser,
            compilationService = compilationService,
            documentProvider = documentProvider,
            sourceNavigator = sourceNavigator,
        )

        EngineType.Core -> CoreLanguageEngine()

        EngineType.OpenRewrite -> OpenRewriteLanguageEngine()
    }
}
