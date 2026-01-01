package com.github.albertocavalcante.groovylsp.engine.impl.core

import com.github.albertocavalcante.groovylsp.engine.adapters.CoreParserAdapter
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
import com.github.albertocavalcante.groovylsp.engine.features.UnifiedHoverProvider
import com.github.albertocavalcante.groovylsp.engine.impl.core.features.CoreCompletionService
import com.github.albertocavalcante.groovylsp.engine.impl.core.features.CoreDefinitionService
import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import org.eclipse.lsp4j.Diagnostic

/**
 * Language Engine implementation backed by the core (JavaParser-based) parser.
 */
class CoreLanguageEngine : LanguageEngine {
    override val id: String = "core"

    private val parserConfiguration = ParserConfiguration()
    private val parser = GroovyParser(parserConfiguration)

    // Type solver for symbol resolution.
    // Note: CombinedTypeSolver/ReflectionTypeSolver are immutable after construction,
    // so sharing across sessions is safe. If solvers are modified at runtime in the
    // future, consider creating per-session instances.
    private val typeSolver = CombinedTypeSolver(ReflectionTypeSolver())

    override fun createSession(request: ParseRequest): LanguageSession {
        val result = parser.parse(request.content)
        val parseUnit = CoreParserAdapter(result, request.uri.toString())
        return CoreLanguageSession(parseUnit, request.content, typeSolver)
    }
}

/**
 * Language Session for the Core Engine.
 */
class CoreLanguageSession(
    private val parseUnit: ParseUnit,
    private val content: String,
    private val typeSolver: CombinedTypeSolver,
) : LanguageSession {

    override val result: ParseResultMetadata = object : ParseResultMetadata {
        override val isSuccess: Boolean = parseUnit.isSuccessful
        override val diagnostics: List<Diagnostic> = parseUnit.diagnostics
    }

    override val features: FeatureSet by lazy {
        object : FeatureSet {
            override val hoverProvider: HoverProvider = UnifiedHoverProvider(parseUnit)
            override val documentSymbolProvider: DocumentSymbolProvider = UnifiedDocumentSymbolProvider(parseUnit)
            override val definitionProvider: DefinitionProvider =
                UnifiedDefinitionProvider(parseUnit, CoreDefinitionService(typeSolver))
            override val completionProvider: CompletionProvider =
                UnifiedCompletionProvider(parseUnit, CoreCompletionService(), content)
        }
    }
}
