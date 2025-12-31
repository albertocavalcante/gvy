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
            return emptyList()
        }

        val visitor = compilationService.getAstModel(uri) ?: return emptyList()
        val symbolTable = compilationService.getSymbolTable(uri) ?: return emptyList()

        val resolver = DefinitionResolver(visitor, symbolTable, compilationService, sourceNavigator)

        val groovyPos = position.toGroovyPosition()
        val result = resolver.findDefinitionAt(uri, groovyPos) ?: return emptyList()

        return listOf(result.toUnifiedDefinition(visitor))
    }

    private fun DefinitionResolver.DefinitionResult.toUnifiedDefinition(visitor: GroovyAstModel): UnifiedDefinition =
        when (this) {
            is DefinitionResolver.DefinitionResult.Source -> {
                val targetUri = this.node.toLspLocation(visitor)?.uri ?: this.uri.toString()
                val targetRange = this.node.toLspRange() ?: Range(Position(0, 0), Position(0, 0))

                UnifiedDefinition(
                    uri = targetUri,
                    range = targetRange,
                    selectionRange = targetRange,
                    kind = DefinitionKind.SOURCE,
                    originSelectionRange = null,
                )
            }

            is DefinitionResolver.DefinitionResult.Binary -> {
                UnifiedDefinition(
                    uri = this.uri.toString(),
                    range = this.range ?: Range(Position(0, 0), Position(0, 0)),
                    selectionRange = this.range ?: Range(Position(0, 0), Position(0, 0)),
                    kind = DefinitionKind.BINARY,
                    originSelectionRange = null,
                )
            }
        }
}
