// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.engine.impl.native.features

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.compiledTextOf
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspLocation
import com.github.albertocavalcante.gvy.gls.converters.toProtocolRange
import com.github.albertocavalcante.gvy.gls.engine.adapters.ParseUnit
import com.github.albertocavalcante.gvy.gls.engine.adapters.UnifiedNode
import com.github.albertocavalcante.gvy.gls.engine.api.DefinitionKind
import com.github.albertocavalcante.gvy.gls.engine.api.DefinitionService
import com.github.albertocavalcante.gvy.gls.engine.api.UnifiedDefinition
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.Position
import java.net.URI

class NativeDefinitionService(
    private val compilationService: GroovyCompilationService,
    private val sourceNavigator: SourceNavigator?,
) : DefinitionService {

    override suspend fun findDefinition(
        node: UnifiedNode?,
        context: ParseUnit,
        position: Position,
    ): List<UnifiedDefinition> {
        // Only a malformed URI is "no definition": resolution errors, an Error above all, propagate (#1311)
        val uri = parseUri(context.uri) ?: return emptyList()
        // One snapshot supplies text, AST and symbols, so a concurrent recompile cannot mix generations.
        val snapshot = compilationService.getCompiledSnapshot(uri) ?: return emptyList()
        val visitor = snapshot.parseResult.astModel
        val symbolTable = snapshot.parseResult.symbolTable
        val resolver = DefinitionResolver(
            visitor,
            symbolTable,
            compilationService,
            sourceNavigator,
            compilationService.getWorkspaceSymbolIndex(),
        )
        // The request position carries UTF-16 columns; the engine's Groovy positions count code points.
        val groovyPosition = position.toGroovyPosition(snapshot.sourceText)
        return resolver.findDefinitionAt(uri, groovyPosition)
            ?.toUnifiedDefinition(visitor) { compilationService.compiledTextOf(it, uri, snapshot.sourceText) }
            ?: emptyList()
    }

    private fun parseUri(uriStr: String): URI? = try {
        URI.create(uriStr)
    } catch (e: IllegalArgumentException) {
        logger.debug(e) { "Failed to parse URI: $uriStr" }
        null
    }

    private fun DefinitionResolver.DefinitionResult.toUnifiedDefinition(
        visitor: GroovyAstModel,
        sourceOf: (String) -> String?,
    ): List<UnifiedDefinition> = when (this) {
        is DefinitionResolver.DefinitionResult.Source -> {
            val targetUri = this.node.toLspLocation(visitor)?.uri ?: this.uri.toString()
            val targetRange = this.node.toProtocolRange(sourceOf(targetUri)) ?: return emptyList()

            listOf(
                UnifiedDefinition(
                    uri = targetUri,
                    range = targetRange,
                    selectionRange = targetRange,
                    kind = DefinitionKind.SOURCE,
                    originSelectionRange = null,
                ),
            )
        }

        is DefinitionResolver.DefinitionResult.Binary -> {
            val targetRange = this.range ?: return emptyList()

            listOf(
                UnifiedDefinition(
                    uri = this.uri.toString(),
                    range = targetRange,
                    selectionRange = targetRange,
                    kind = DefinitionKind.BINARY,
                    originSelectionRange = null,
                ),
            )
        }
    }
}

private val logger = KotlinLogging.logger {}
