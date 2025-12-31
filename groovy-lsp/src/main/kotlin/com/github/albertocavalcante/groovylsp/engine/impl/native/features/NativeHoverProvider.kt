package com.github.albertocavalcante.groovylsp.engine.impl.native.features

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.engine.api.HoverProvider
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.api.ParseResult
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import com.github.albertocavalcante.groovylsp.providers.hover.HoverProvider as DelegateHoverProvider

/**
 * Native hover provider that delegates to the existing HoverProvider implementation.
 * This ensures all existing hover logic (including Jenkins-specific handling) continues to work.
 */
class NativeHoverProvider(
    @Suppress("UNUSED_PARAMETER") private val parseResult: ParseResult,
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val sourceNavigator: SourceNavigator? = null,
) : HoverProvider {

    private val logger = LoggerFactory.getLogger(NativeHoverProvider::class.java)

    // Delegate to existing HoverProvider which has all the domain logic
    private val delegate = DelegateHoverProvider(
        compilationService,
        documentProvider,
        sourceNavigator,
    )

    override suspend fun getHover(params: HoverParams): Hover = try {
        delegate.provideHover(params.textDocument.uri, params.position) ?: emptyHover()
    } catch (e: Exception) {
        logger.error("Error providing hover", e)
        emptyHover()
    }

    private fun emptyHover() = Hover().apply {
        contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, ""))
    }
}
