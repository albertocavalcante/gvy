// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
import com.github.albertocavalcante.nativeapi.ParseMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.nio.file.Path

internal fun interface RenameDocumentSource {
    fun read(uri: URI): DocumentSnapshot?
}

/** One rename request's captured input. Readers run outside compilation and document-store locks. */
internal class RenameSourceSnapshots(private val source: RenameDocumentSource, val request: RenameRequest) {
    val roots = (listOfNotNull(request.source.workspace.root) + request.extraFolders).distinct()
    lateinit var guard: CrossFileUseGuard
    private val consumed = mutableListOf<suspend () -> Boolean>()
    private val publications = mutableListOf<() -> Boolean>()
    private val captured = LinkedHashMap<URI, DocumentSnapshot>()

    fun capture(uri: URI): DocumentSnapshot = captured.getOrPut(uri) { read(uri) }

    fun hold(uri: URI, snapshot: DocumentSnapshot) {
        captured[uri] = snapshot
    }

    fun observe(current: suspend () -> Boolean) {
        consumed.add(current)
    }

    fun observePublication(current: () -> Boolean) {
        publications.add(current)
    }

    fun validatePublications() {
        if (!request.authority.current() || !publications.all { it() }) changed(request.source.uri)
    }

    suspend fun validate() {
        if (consumed.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (current in consumed) {
                    ensureActive()
                    if (!current()) changed(request.source.uri)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        captured.forEach { (uri, expected) ->
            if (read(uri) != expected) changed(uri)
        }
    }

    private fun read(uri: URI): DocumentSnapshot = source.read(uri) ?: changed(uri)

    fun changed(uri: URI): Nothing = renameChanged(uri)
}

/** Attach only facts the cross-file guard used to decide if this symbol can be renamed. */
internal class RenameScanWitness(
    private val request: RenameRequest,
    private val compiled: CompiledDocumentSnapshot,
    private val sources: RenameSourceSnapshots,
    private val compilation: GroovyCompilationService,
    private val disk: WorkspaceTextCache,
) {
    private var rebuiltRows = 0
    val references get() = request.references

    fun begin(walks: WorkspaceWalkCache) {
        sources.observePublication { walks.invalidations == request.walkGeneration }
        sources.observePublication(references.isCurrent)
    }

    suspend fun qualifies(
        uri: URI,
        indexed: SemanticDocumentSnapshot,
        symbolId: String,
        budget: Long,
        consume: (Int) -> Unit,
    ): Boolean {
        val text = indexed.sourceText ?: return false
        if (!sourceMatches(uri, text, budget, consume)) return false
        return publicationQualifies(uri, indexed) || rebuildUse(uri, text, symbolId)
    }

    private fun sourceMatches(uri: URI, text: String, budget: Long, consume: (Int) -> Unit): Boolean {
        val buffer = request.buffers?.documents?.get(uri) ?: request.source.documents[uri]
        if (buffer != null) return buffer.content == text
        val path = Path.of(uri)
        val physical = realKey(path)
        val observed = disk.readCurrent(path, budget)
        if (observed is DiskText.Text) consume(observed.text.length)
        sources.observe { realKey(path) == physical && disk.readCurrent(path, budget) == observed }
        return observed is DiskText.Text && observed.text == text
    }

    private fun publicationQualifies(uri: URI, indexed: SemanticDocumentSnapshot): Boolean {
        val indexer = request.source.dependencies.semanticIndexer
        val publication = indexer?.currentReferencePublication(uri) ?: return false
        val proof = request.readiness.qualifyMember(uri, indexed, compiled) ?: return false
        sources.observe { proof() }
        sources.observePublication { indexer.currentReferencePublication(uri) == publication }
        return true
    }

    /** Opening an unchanged companion retires the old index producer. Confirm a reported use with current bindings. */
    private suspend fun rebuildUse(uri: URI, text: String, symbolId: String): Boolean {
        if (rebuiltRows >= MAX_REPORTED) return false
        rebuiltRows++
        val source = compilation.captureSnapshotSource(uri)
        val acquired = compilation.acquireSnapshot(uri, Phases.SEMANTIC_ANALYSIS, ParseMode.WORKSPACE)
        return when (acquired) {
            is SnapshotAcquisition.Ready -> confirmsUse(acquired, source, text, symbolId)
            is SnapshotAcquisition.Partial -> {
                sources.observePublication(acquired.isCurrent)
                false
            }
            SnapshotAcquisition.Missing, SnapshotAcquisition.Stale -> sources.changed(uri)
            is SnapshotAcquisition.Failure -> throw acquired.cause
        }
    }

    private suspend fun confirmsUse(
        acquired: SnapshotAcquisition.Ready,
        source: SnapshotSource,
        text: String,
        symbolId: String,
    ): Boolean {
        sources.observePublication { acquired.isCurrent() && source.workerCurrent() && source.isCurrent() }
        val answer = acquired.query { held ->
            if (held.sourceText != text) sources.changed(source.uri)
            val physical = held.requireParseInputs()
            sources.observe {
                val caller = currentCoroutineContext()
                physical.isCurrent(source, caller::ensureActive)
            }
            val module = checkNotNull(held.parseResult.ast)
            compiled.referenceProducer?.sameEnvironment(held.referenceProducer) == true &&
                SemanticDocumentBuilder(module, source.uri).build().findOccurrences(symbolId).isNotEmpty()
        }
        return when (answer) {
            is SnapshotQuery.Answer -> answer.value
            SnapshotQuery.Stale, SnapshotQuery.Missing -> sources.changed(source.uri)
            is SnapshotQuery.Failure -> throw answer.cause
        }
    }

    fun observe(check: suspend () -> Boolean) = sources.observe(check)
}

internal fun renameChanged(uri: URI): Nothing = throw ResponseErrorException(
    ResponseError(ResponseErrorCode.ContentModified, "Source changed or is unavailable during rename: $uri", null),
)
