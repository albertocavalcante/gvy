package com.github.albertocavalcante.groovylsp.engine.impl.native.features

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.converters.toLspLocation
import com.github.albertocavalcante.groovylsp.converters.toLspRange
import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionKind
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionService
import com.github.albertocavalcante.groovylsp.engine.api.UnifiedDefinition
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
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
        val uri = try {
            URI.create(uriStr)
        } catch (e: Exception) {
            logger.debug("Failed to parse URI: {}", uriStr, e)
            return emptyList()
        }

        val visitor = compilationService.getAstModel(uri) ?: return emptyList()
        val symbolTable = compilationService.getSymbolTable(uri) ?: return emptyList()

        val resolver = DefinitionResolver(visitor, symbolTable, compilationService, sourceNavigator)

        val groovyPos = position.toGroovyPosition()
        val result = resolver.findDefinitionAt(uri, groovyPos) ?: return emptyList()

        return result.toUnifiedDefinition(visitor)
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

private val logger = org.slf4j.LoggerFactory.getLogger(NativeDefinitionService::class.java)
