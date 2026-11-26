// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.RequestParseInputs
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.compilation.SymbolIndexingService
import com.github.albertocavalcante.gvy.gls.compilation.deliveryInputsCurrent
import com.github.albertocavalcante.gvy.gls.compilation.inputsCurrent
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** The source/index view consumed by one workspace/symbol request, including misses in its ordered scan. */
@Suppress("DEPRECATION") // SymbolInformation is the existing protocol response.
internal class WorkspaceSymbolRequest(
    private val compilation: GroovyCompilationService,
    private val index: SymbolIndexingService,
    private val query: String,
    private val limit: Int,
) {
    private val inventory = compilation.documents.captureDocuments()
    private val environmentCurrent = index.captureEnvironment()
    private val checks = mutableListOf<() -> Boolean>()

    private fun current() =
        environmentCurrent() && inventory.identity === compilation.documents.captureDocuments().identity

    suspend fun search(ready: suspend () -> Unit): List<SymbolInformation> {
        val caller = currentCoroutineContext()
        if (!caller.inputsCurrent(::current)) changed()
        ready()
        val (rows, publicationCurrent) = index.captureRows()
        val hidden = index.hiddenRows()
        fun identitiesCurrent() = publicationCurrent() && current()
        if (!caller.inputsCurrent(::identitiesCurrent)) changed()
        val result = runSuspendCatching {
            buildList {
                for ((uri, row) in rows.toSortedMap()) {
                    caller.ensureActive()
                    if (uri in hidden) continue
                    val source = compilation.captureSnapshotSource(uri)
                    val selected = select(source, row, caller::ensureActive) ?: continue
                    val hits = searchWorkspaceSymbols(mapOf(uri to selected.index), query, limit - size) {
                        selected.content
                    }
                    addAll(hits)
                    if (size >= limit) break
                }
            }
        }
        if (!deliveryInputsCurrent(::identitiesCurrent) {
                checks.all { it() } && hidden == index.hiddenRows()
            }
        ) changed()
        return result.getOrThrow()
    }

    private class Row(val content: String, val index: SymbolIndex)

    private suspend fun select(
        source: SnapshotSource,
        row: SymbolIndexingService.Prepared,
        checkCancelled: () -> Unit,
    ): Row? {
        val inputs = row.inputs
        if (inputs != null) {
            val current = {
                currentText(source) == row.content && inputs.physical.isCurrent(source, row.content, checkCancelled) &&
                    inputs.producerCurrent(source) && source.workerCurrent() && source.isCurrent()
            }
            if (current()) {
                checks.add(current)
                return Row(row.content, row.index)
            }
        }
        // A retained physical declaration may predate a cache clear. Rebuild only a scanned, unqualified row
        // from its current source. Never use new text to translate coordinates produced by the old index.
        return when (val acquisition = compilation.acquireSymbolRow(source)) {
            is SnapshotAcquisition.Available -> {
                val held = acquisition.snapshot
                val physical = RequestParseInputs(held.parseResult)
                val current = {
                    currentText(source) == held.sourceText &&
                        physical.isCurrent(source, held.sourceText, checkCancelled) &&
                        acquisition.isCurrent() && source.workerCurrent() && source.isCurrent()
                }
                if (!current()) changed()
                checks.add(current)
                Row(held.sourceText, SymbolIndex().buildFromVisitor(held.parseResult.astModel))
            }
            SnapshotAcquisition.Missing -> {
                checks.add { currentText(source) == null && source.isCurrent() }
                null
            }
            SnapshotAcquisition.Stale -> changed()
            is SnapshotAcquisition.Failure -> throw acquisition.cause
        }
    }

    private fun currentText(source: SnapshotSource): String? {
        source.buffer?.let { return it.snapshot.content }
        val path = UriPathConverter.toPath(source.uri) ?: return null
        return try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (attributes.isRegularFile) Files.readString(path) else null
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun changed(): Nothing = throw ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Workspace changed during symbol search", null),
    )
}
