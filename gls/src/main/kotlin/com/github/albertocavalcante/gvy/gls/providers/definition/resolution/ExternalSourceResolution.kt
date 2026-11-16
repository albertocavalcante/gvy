// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import com.github.albertocavalcante.gvy.gls.sources.SourceLookupResult
import com.github.albertocavalcante.gvy.gls.sources.SourceSpan
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

/** Acquisition failure and retirement are protocol outcomes, never "no declaration exists". */
internal fun sourceResolution(result: SourceLookupResult, name: String, strategy: String): ResolutionResult =
    when (result) {
        is SourceLookupResult.Located -> SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Binary(
                result.uri,
                name,
                range = result.definition.nameRange.toLspRange(),
                declarationRange = result.definition.declarationRange.toLspRange(),
            ),
        )
        is SourceLookupResult.FileOnly ->
            SymbolResolutionStrategy.notFound(
                "Source declaration is unavailable",
                strategy,
            )
        is SourceLookupResult.Unavailable ->
            SymbolResolutionStrategy.notFound(
                "Source unavailable: ${result.reason}",
                strategy,
            )
        SourceLookupResult.Ambiguous ->
            SymbolResolutionStrategy.notFound(
                "Source origin is ambiguous",
                strategy,
            )
        SourceLookupResult.Stale -> throw ResponseErrorException(
            ResponseError(ResponseErrorCode.ContentModified, "Dependency source context changed", null),
        )
        is SourceLookupResult.Failed -> throw ResponseErrorException(
            ResponseError(ResponseErrorCode.RequestFailed, "Dependency source lookup failed: ${result.failure}", null),
        )
    }

private fun SourceSpan.toLspRange(): Range = Range(
    Position(startLine, startCharacter),
    Position(endLine, endCharacter),
)
