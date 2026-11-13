// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** Bounded type browsing in managed dependency sources, independent of a Java language server. */
internal class SourceBrowserService(
    scope: CoroutineScope,
    private val registry: ExternalSourceRegistry,
    private val beginLookup: suspend (SourceDocumentDescriptor) -> SourceBrowserLookupSession?,
    buildIndex: suspend (String, String, () -> Unit) -> SourceTypeIndex = { language, content, checkCancelled ->
        when (language) {
            "java" -> JavaSourceTypeIndex.build(content, checkCancelled)
            "groovy" -> GroovySourceTypeIndex.build(content, checkCancelled)
            else -> SourceTypeIndex { null }
        }
    },
) : AutoCloseable {
    private val indexes = SourceTypeIndexCache(scope, buildIndex)

    override fun close() {
        indexes.close()
    }

    suspend fun definition(uri: URI, position: Position): List<LocationLink> {
        val descriptor = registry.descriptor(uri) ?: return emptyList()
        val content = registry.content(uri) ?: return emptyList()
        currentCoroutineContext().ensureActive()
        val index = indexes.index(descriptor.languageId, descriptor.contentDigest, content)
        currentCoroutineContext().ensureActive()
        val reference = index.at(position) ?: return emptyList()
        currentCoroutineContext().ensureActive()
        reference.localDefinition?.let { return currentLink(uri, uri, it, reference.range) }
        val session = beginLookup(descriptor) ?: throw staleContext()
        currentCoroutineContext().ensureActive()
        if (!session.isCurrent()) throw staleContext()
        for (tier in reference.candidates) {
            when (val result = locateTier(tier, session)) {
                TierResult.Absent -> Unit
                TierResult.Unknown -> return emptyList()
                is TierResult.Located -> return currentLink(
                    uri,
                    result.value.uri,
                    result.value.definition,
                    reference.range,
                    validateCurrent = session.validateCurrent,
                )
            }
        }
        return emptyList()
    }

    private suspend fun locateTier(candidates: List<String>, session: SourceBrowserLookupSession): TierResult {
        if (candidates.size > MAX_CANDIDATES) return TierResult.Unknown
        val located = mutableListOf<SourceLookupResult.Located>()
        var uncertain = false
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if (!session.isCurrent()) throw staleContext()
            val result = session.locateType(candidate)
            currentCoroutineContext().ensureActive()
            if (!session.isCurrent()) throw staleContext()
            when (result) {
                is SourceLookupResult.Located -> {
                    if (
                        result.binaryName == candidate ||
                        ('$' !in candidate && result.binaryName.replace('$', '.') == candidate)
                    ) {
                        located.add(result)
                    } else {
                        uncertain = true
                    }
                }
                SourceLookupResult.Stale -> throw staleContext()
                SourceLookupResult.Ambiguous -> return TierResult.Unknown
                is SourceLookupResult.Failed -> throw ResponseErrorException(
                    ResponseError(ResponseErrorCode.RequestFailed, "Dependency source lookup failed", null),
                )
                is SourceLookupResult.FileOnly -> uncertain = true
                is SourceLookupResult.Unavailable -> if (result.reason != MissingSourceReason.BINARY_TYPE_NOT_PRESENT) {
                    uncertain = true
                }
            }
        }
        val distinct = located.distinctBy { it.uri to it.binaryName }
        if (uncertain || distinct.size > 1) return TierResult.Unknown
        return distinct.singleOrNull()?.let { TierResult.Located(it) } ?: TierResult.Absent
    }

    private sealed interface TierResult {
        data object Absent : TierResult
        data object Unknown : TierResult
        data class Located(val value: SourceLookupResult.Located) : TierResult
    }

    private suspend fun currentLink(
        source: URI,
        target: URI,
        definition: SourceDefinition,
        origin: SourceSpan,
        isCurrent: () -> Boolean = { true },
        validateCurrent: suspend () -> Boolean = { true },
    ): List<LocationLink> {
        if (registry.content(source) == null) {
            throw ResponseErrorException(
                ResponseError(ResponseErrorCode.ContentModified, "Dependency source content changed", null),
            )
        }
        if (!isCurrent()) throw staleContext()
        if (!validateCurrent()) throw staleContext()
        return listOf(link(target, definition, origin))
    }

    private fun staleContext(): ResponseErrorException = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Dependency source context changed", null),
    )

    private fun link(uri: URI, definition: SourceDefinition, origin: SourceSpan): LocationLink = LocationLink().apply {
        targetUri = uri.toString()
        targetRange = definition.declarationRange.toLsp()
        targetSelectionRange = definition.nameRange.toLsp()
        originSelectionRange = origin.toLsp()
    }

    private fun SourceSpan.toLsp(): Range = Range(
        Position(startLine, startCharacter),
        Position(endLine, endCharacter),
    )

    private companion object {
        const val MAX_CANDIDATES = 32
    }
}
