package com.github.albertocavalcante.groovylsp.engine.impl.native.features

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.engine.api.HoverProvider
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.api.ParseResult
import kotlinx.coroutines.CancellationException
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
 *
 * @param parseResult The parse result for this session (reserved for future direct AST usage)
 */
class NativeHoverProvider(
    @Suppress("UNUSED_PARAMETER") // TODO: Use parseResult directly instead of delegating
    private val parseResult: ParseResult,
    compilationService: GroovyCompilationService,
    documentProvider: DocumentProvider,
    sourceNavigator: SourceNavigator? = null,
) : HoverProvider {

    private val logger = LoggerFactory.getLogger(NativeHoverProvider::class.java)

    // Delegate to existing HoverProvider which has all the domain logic
    private val delegate = DelegateHoverProvider(
        compilationService,
        documentProvider,
        sourceNavigator,
    )

    override suspend fun getHover(params: HoverParams): Hover = runCatching {
        delegate.provideHover(params.textDocument.uri, params.position) ?: emptyHover()
    }
        .onFailure { throwable ->
            when (throwable) {
                is CancellationException -> throw throwable
                is Error -> throw throwable
                else -> logger.error("Error providing hover", throwable)
            }
        }
        .getOrDefault(emptyHover())

    private fun emptyHover() = Hover().apply {
        contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, ""))
    }
}
