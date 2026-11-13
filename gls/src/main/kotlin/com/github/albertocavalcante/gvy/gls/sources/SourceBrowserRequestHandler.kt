// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import java.net.URI
import java.util.concurrent.CompletableFuture

data class SourceDocumentParams(val uri: String)

data class SourceDefinitionParams(val uri: String, val position: Position)

data class SourceResolveParams(
    val documentUri: String,
    val position: Position,
    val allowDownload: Boolean = false,
    val documentVersion: Int? = null,
)

data class SourceAttachParams(
    val documentUri: String,
    val position: Position,
    val sourcesUri: String,
    val expectedSha256: String? = null,
    val documentVersion: Int? = null,
)

enum class SourceResolveStatus { LOCATED, FILE_ONLY, UNAVAILABLE, AMBIGUOUS, STALE, FAILED }

data class SourceResolveResult(
    val status: SourceResolveStatus,
    val locations: List<LocationLink> = emptyList(),
    val reason: String? = null,
    val message: String? = null,
    val fileUri: String? = null,
) {
    init {
        val describesSource = when (status) {
            SourceResolveStatus.LOCATED -> locations.isNotEmpty() && fileUri == null
            SourceResolveStatus.FILE_ONLY ->
                locations.isEmpty() &&
                    !fileUri.isNullOrBlank() &&
                    URI.create(fileUri).scheme in setOf(ExternalSourceRegistry.SCHEME, "file")
            else -> locations.isEmpty() && fileUri == null
        }
        require(describesSource) {
            "Source resolution must describe either an exact declaration, a known file or no source"
        }
    }
}

data class SourceContentResult(
    val uri: String,
    val content: String,
    val languageId: String,
    val displayName: String,
    val readOnly: Boolean = true,
)

data class SourceDetailsResult(
    val uri: String,
    val displayName: String,
    val originLabel: String,
    val languageId: String,
    val sourceDigest: String,
    val contentDigest: String,
    val suggestedFileName: String,
    val readOnly: Boolean,
    val contextLabel: String? = null,
)

/** LSP transport adapter. Requests never launch builds or acquire sources merely to show a document. */
class SourceBrowserRequestHandler(
    private val scope: CoroutineScope,
    private val registry: ExternalSourceRegistry,
    beginLookup: suspend (SourceDocumentDescriptor) -> SourceBrowserLookupSession?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val resolveSource: suspend (SourceResolveParams) -> SourceResolveResult = { unavailable() },
    private val attachSource: suspend (SourceAttachParams) -> SourceResolveResult = { unavailable() },
) : AutoCloseable {
    private val browser = SourceBrowserService(scope, registry, beginLookup)

    override fun close() {
        browser.close()
    }

    fun resolve(params: SourceResolveParams): CompletableFuture<SourceResolveResult> = scope.future {
        resolveSource(params)
    }

    fun attach(params: SourceAttachParams): CompletableFuture<SourceResolveResult> = scope.future {
        attachSource(params)
    }

    fun isManaged(params: SourceDocumentParams): CompletableFuture<Boolean> = scope.future {
        withContext(ioDispatcher) {
            try {
                registry.isManagedDestination(URI.create(params.uri))
            } catch (_: IllegalArgumentException) {
                true
            }
        }
    }

    fun content(params: SourceDocumentParams): CompletableFuture<SourceContentResult?> = scope.future {
        withContext(ioDispatcher) {
            val uri = sourceUri(params.uri) ?: return@withContext null
            if (uri.scheme != ExternalSourceRegistry.SCHEME) return@withContext null
            val descriptor = registry.descriptor(uri) ?: return@withContext null
            val content = registry.content(uri) ?: return@withContext null
            SourceContentResult(params.uri, content, descriptor.languageId, descriptor.displayName)
        }
    }

    fun definition(params: SourceDefinitionParams): CompletableFuture<List<LocationLink>> = scope.future {
        withContext(ioDispatcher) {
            val uri = sourceUri(params.uri) ?: return@withContext emptyList()
            browser.definition(uri, params.position)
        }
    }

    fun details(params: SourceDocumentParams): CompletableFuture<SourceDetailsResult?> = scope.future {
        withContext(ioDispatcher) {
            val uri = sourceUri(params.uri) ?: return@withContext null
            val descriptor = registry.descriptor(uri) ?: return@withContext null
            SourceDetailsResult(
                params.uri,
                descriptor.displayName,
                label(descriptor.origin),
                descriptor.languageId,
                descriptor.sourceDigest,
                descriptor.contentDigest,
                descriptor.entryName.substringAfterLast('/'),
                uri.scheme == ExternalSourceRegistry.SCHEME,
                contextLabel = contextLabel(descriptor.context.documentUri),
            )
        }
    }

    private fun sourceUri(raw: String): URI? = try {
        URI.create(raw).takeIf(registry::isManaged)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun contextLabel(uri: URI?): String? {
        if (uri == null) return null
        val label = uri.path?.takeIf(String::isNotBlank) ?: uri.toString()
        return if (label.length <= MAX_CONTEXT_LABEL) {
            label
        } else {
            label.take(MAX_CONTEXT_LABEL / 2 - 1) + "…" + label.takeLast(MAX_CONTEXT_LABEL / 2)
        }
    }

    private fun label(origin: SourceOrigin): String = when (origin) {
        is SourceOrigin.Archive -> origin.coordinates?.let { it.group + ":" + it.module + ":" + it.version }
            ?: "Attached dependency source"
        is SourceOrigin.Bundled -> "Bundled " + origin.componentId
        is SourceOrigin.Jdk -> "JDK " + origin.module
        is SourceOrigin.Unknown -> "Dependency source"
    }

    private companion object {
        const val MAX_CONTEXT_LABEL = 2048

        fun unavailable() = SourceResolveResult(
            SourceResolveStatus.UNAVAILABLE,
            reason = "NOT_SUPPORTED",
            message = "Source acquisition is unavailable for this server context.",
        )
    }
}
