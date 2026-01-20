package com.github.albertocavalcante.gvy.gls.engine.impl.native.features

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspLocation
import com.github.albertocavalcante.gvy.gls.converters.toLspRange
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
        val uriStr = context.uri
        return runCatching { URI.create(uriStr) }
            .onFailure { e -> logger.debug(e) { "Failed to parse URI: $uriStr" } }
            .map { uri ->
                val visitor = compilationService.getAstModel(uri)
                val symbolTable = compilationService.getSymbolTable(uri)

                if (visitor == null || symbolTable == null) {
                    emptyList()
                } else {
                    val resolver = DefinitionResolver(
                        visitor,
                        symbolTable,
                        compilationService,
                        sourceNavigator,
                        compilationService.getWorkspaceSymbolIndex(),
                    )
                    val groovyPos = position.toGroovyPosition()
                    resolver.findDefinitionAt(uri, groovyPos)?.toUnifiedDefinition(visitor) ?: emptyList()
                }
            }
            .getOrElse { emptyList() }
    }

    private fun DefinitionResolver.DefinitionResult.toUnifiedDefinition(
        visitor: GroovyAstModel,
    ): List<UnifiedDefinition> = when (this) {
        is DefinitionResolver.DefinitionResult.Source -> {
            val targetUri = this.node.toLspLocation(visitor)?.uri ?: this.uri.toString()
            val targetRange = this.node.toLspRange() ?: return emptyList()

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
