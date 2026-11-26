// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.indexing.CapturedWorkspaceReferences
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Capture before index preparation. A successful best-effort compile alone cannot establish reference coverage. */
internal class ReferenceReadiness(
    private val source: SnapshotSource,
    private val index: WorkspaceSymbolIndex,
    private val currentPublication: (URI) -> ReferencePublication?,
    private val selectSources: (URI, WorkspaceModel) -> List<Path>,
    private val readOrigin: (URI) -> String? = ::readDiskOrigin,
) {
    val sourceIdentity: String get() = source.sourceIdentity
    private val hidden = index.referenceShadows()

    suspend fun inputsCurrent(): Boolean = currentCoroutineContext().inputsCurrent(source::isCurrent)

    /** An unchanged source can still need a new compiler invocation after its companion buffers change. */
    suspend fun prepareOpenSources(compilation: GroovyCompilationService) {
        withTimeoutOrNull(CompilationEnsurer.DEFAULT_TIMEOUT_MS) {
            for ((uri, buffer) in source.documents) {
                ensureActive()
                if (!inputsCurrent()) return@withTimeoutOrNull
                val producer = currentPublication(uri)?.producer ?: continue
                if (!currentCoroutineContext().inputsCurrent {
                        producer.isCurrent(source, uri, buffer.content, selectSources)
                    }
                ) {
                    compilation.ensureCompiled(uri, buffer)
                }
            }
            for (path in source.workspace.sources) {
                ensureActive()
                if (!inputsCurrent()) return@withTimeoutOrNull
                val uri = path.toUri()
                if (uri in source.documents || uri in hidden) continue
                val publication = currentPublication(uri) ?: continue
                val producer = publication.producer ?: continue
                val mode = producer.mode ?: continue
                if (publication.diskConfirmed &&
                    !producer.ownsWindow(source.workspace, source.ticket, source.inventory.identity)
                ) {
                    compilation.refreshClosedReferences(path, mode)
                }
            }
        }
    }

    suspend fun <T> query(compute: suspend (QualifiedReferences) -> T): SnapshotQuery<T> {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val references = index.captureReferences(hidden)
        val producers = mutableMapOf<URI, ReferenceProducer>()
        fun isCurrent(): Boolean {
            val sameBindings = producers.all { (uri, producer) ->
                val indexed = checkNotNull(references.getDocumentSnapshot(uri)?.sourceText)
                producer.isCurrent(source, uri, indexed, selectSources)
            }
            // A strategy or shadow lookup may retire a buffer while it runs.
            return sameBindings && references.isCurrent() && source.isCurrent()
        }
        if (!caller.inputsCurrent(::isCurrent)) return SnapshotQuery.Stale
        val unavailable = firstUnavailable(references, producers)
        return when {
            unavailable == null -> queryCurrentSnapshot(::isCurrent) {
                compute(QualifiedReferences(references, producers))
            }
            !caller.inputsCurrent(::isCurrent) -> SnapshotQuery.Stale
            else -> SnapshotQuery.Failure(
                ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, unavailable, null)),
            )
        }
    }

    private suspend fun firstUnavailable(
        references: CapturedWorkspaceReferences,
        producers: MutableMap<URI, ReferenceProducer>,
    ): String? = withContext(Dispatchers.IO) {
        val required = buildSet {
            addAll(source.documents.keys)
            source.workspace.sources.mapTo(this) { it.toUri() }
            removeAll(hidden)
            add(source.uri)
            addAll(references.visibleDocuments)
        }
        var environment: ReferenceProducer? = null
        for (uri in required) {
            ensureActive()
            val indexed = references.getDocumentSnapshot(uri) ?: return@withContext unavailable(uri)
            val content = indexed.sourceText ?: return@withContext unavailable(uri)
            val publication = currentPublication(uri) ?: return@withContext unavailable(uri)
            val producer = publication.producer ?: return@withContext unavailable(uri)
            if (!publication.covers(indexed.document) ||
                !matchesSource(uri, content, publication) ||
                !currentCoroutineContext().inputsCurrent { producer.isCurrent(source, uri, content, selectSources) }
            ) {
                return@withContext unavailable(uri)
            }
            if (environment == null) {
                environment = producer
            } else if (environment !== producer && !environment.sameEnvironment(producer)) {
                return@withContext "Reference index combines different compiler environments at $uri"
            }
            producers[uri] = producer
        }
        null
    }

    private fun unavailable(uri: URI) = "Reference index unavailable for $uri"

    private fun matchesSource(uri: URI, indexed: String, publication: ReferencePublication): Boolean {
        val buffer = source.documents[uri]
        if (buffer != null) return indexed == buffer.content
        // A watcher retires this evidence before refresh. Only the requested closed origin can fall back
        // to an exact body comparison. Reading every unrelated file here would make navigation workspace-sized IO.
        val path = UriPathConverter.toPath(uri)
        return if (path != null && Files.isRegularFile(path)) {
            publication.diskConfirmed || (uri == source.uri && indexed == readOrigin(uri))
        } else {
            false
        }
    }
}

private fun readDiskOrigin(uri: URI): String? {
    val path = UriPathConverter.toPath(uri) ?: return null
    return try {
        Files.readString(path)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
