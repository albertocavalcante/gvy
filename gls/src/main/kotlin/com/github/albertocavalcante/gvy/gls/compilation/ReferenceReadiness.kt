// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.indexing.CapturedWorkspaceReferences
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
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

    /** Readiness may rebuild open entries whose unchanged text was indexed against an earlier companion set. */
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

    fun captureMembersView(): CapturedWorkspaceReferences = index.captureReferences(hidden)

    fun qualifyMember(
        uri: URI,
        indexed: SemanticDocumentSnapshot,
        origin: CompiledDocumentSnapshot,
    ): (() -> Boolean)? {
        val published = currentPublication(uri) ?: return null
        val content = indexed.sourceText ?: return null
        val producer = published.producer ?: return null
        if (!published.covers(indexed.document) ||
            !producer.sameEnvironment(origin.referenceProducer) ||
            !matchesSource(uri, content, published)
        ) {
            return null
        }
        val current = { producer.isCurrent(source, uri, content, selectSources) && source.isCurrent() }
        return current.takeIf { it() }
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
