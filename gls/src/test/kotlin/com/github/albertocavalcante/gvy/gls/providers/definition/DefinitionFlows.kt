// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position

/**
 * Test convenience, not production behaviour: the definitions at [position] as a flow that is EMPTY when the
 * lookup failed (the failure is still reported to telemetry). Production callers use `lookupDefinitions`, which
 * keeps a failed lookup apart from "nothing found"; tests that only care about the targets use this.
 */
internal fun DefinitionProvider.provideDefinitions(uri: String, position: Position): Flow<Location> = flow {
    emitAll(lookupDefinitions(uri, position).getOrElse { emptyList() }.asFlow())
}

/** Test convenience like [provideDefinitions], for [LocationLink]s: empty when the lookup failed. */
internal fun DefinitionProvider.provideDefinitionLinks(uri: String, position: Position): Flow<LocationLink> = flow {
    emitAll(lookupDefinitionLinks(uri, position).getOrElse { emptyList() }.asFlow())
}
