package com.github.albertocavalcante.groovylsp.engine.impl.rewrite

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.RewriteParserAdapter
import com.github.albertocavalcante.groovylsp.engine.api.CompletionProvider
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionProvider
import com.github.albertocavalcante.groovylsp.engine.api.DocumentSymbolProvider
import com.github.albertocavalcante.groovylsp.engine.api.FeatureSet
import com.github.albertocavalcante.groovylsp.engine.api.HoverProvider
import com.github.albertocavalcante.groovylsp.engine.api.LanguageEngine
import com.github.albertocavalcante.groovylsp.engine.api.LanguageSession
import com.github.albertocavalcante.groovylsp.engine.api.ParseResultMetadata
import com.github.albertocavalcante.groovylsp.engine.features.UnifiedDocumentSymbolProvider
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.provider.RewriteParserProvider
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import java.net.URI
import java.nio.file.Paths

/**
 * Language Engine implementation backed by the OpenRewrite parser.
 *
 * This engine uses OpenRewrite's lossless LST for:
 * - Comment preservation
 * - Future refactoring support
 *
 * Trade-offs:
 * - Limited feature set compared to Native engine (no type resolution)
 * - No error recovery (fails on syntax errors)
 */
class OpenRewriteLanguageEngine : LanguageEngine {
    override val id: String = "rewrite"

    private val parserProvider = RewriteParserProvider()

    override fun createSession(request: ParseRequest): LanguageSession {
        val path = try {
            Paths.get(request.uri)
        } catch (_: java.nio.file.InvalidPathException) {
            null
        }
        val parseUnit = parserProvider.parse(request.content, path)
        val adapter = RewriteParserAdapter(parseUnit, request.uri.toString())
        return OpenRewriteLanguageSession(adapter)
    }

    override fun createSession(uri: URI, content: String): LanguageSession = createSession(ParseRequest(uri, content))
}

/**
 * Language Session for the OpenRewrite Engine.
 */
class OpenRewriteLanguageSession(private val parseUnit: ParseUnit) : LanguageSession {

    override val result: ParseResultMetadata = object : ParseResultMetadata {
        override val isSuccess: Boolean = parseUnit.isSuccessful
        override val diagnostics: List<Diagnostic> = parseUnit.diagnostics
    }

    override val features: FeatureSet by lazy {
        object : FeatureSet {
            // Basic hover that shows node info
            override val hoverProvider: HoverProvider = RewriteHoverProvider(parseUnit)

            // Document symbols provided via unified provider
            override val documentSymbolProvider: DocumentSymbolProvider = UnifiedDocumentSymbolProvider(parseUnit)

            // Definition not yet supported
            override val definitionProvider: DefinitionProvider? = null

            // Completion not yet supported
            override val completionProvider: CompletionProvider? = null
        }
    }
}

/**
 * Basic hover provider for OpenRewrite engine.
 * Shows node information at the cursor position.
 */
private class RewriteHoverProvider(private val parseUnit: ParseUnit) : HoverProvider {
    override suspend fun getHover(params: HoverParams): Hover {
        val position = params.position

        val nodeInfo = parseUnit.nodeAt(position)
        val markupContent = if (nodeInfo != null) {
            MarkupContent(
                MarkupKind.MARKDOWN,
                buildString {
                    append("**${nodeInfo.kind}**")
                    nodeInfo.name?.let { append(": `$it`") }
                },
            )
        } else {
            MarkupContent(MarkupKind.PLAINTEXT, "")
        }
        return Hover(markupContent)
    }
}
