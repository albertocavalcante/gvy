package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionProvider
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionService
import com.github.albertocavalcante.groovylsp.engine.api.UnifiedDefinition
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.jsonrpc.messages.Either

class UnifiedDefinitionProvider(private val parseUnit: ParseUnit, private val definitionService: DefinitionService) :
    DefinitionProvider {

    override suspend fun getDefinition(params: DefinitionParams): Either<List<Location>, List<LocationLink>> {
        val position = params.position
        val node = parseUnit.nodeAt(position)

        val definitions = definitionService.findDefinition(node, parseUnit, position)

        if (definitions.isEmpty()) {
            return Either.forLeft(emptyList())
        }

        val links = definitions.map { it.toLspLocationLink() }
        return Either.forRight(links)
    }

    private fun UnifiedDefinition.toLspLocationLink(): LocationLink = LocationLink(
        this.uri,
        this.range, // targetRange
        this.selectionRange, // targetSelectionRange
        this.originSelectionRange, // originSelectionRange
    )
}
