// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.IndexedSourceText
import com.github.albertocavalcante.gvy.gls.compilation.IndexedTextRead
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
                    val selected = if (uri in hidden) {
                        null
                    } else {
                        select(compilation.captureSnapshotSource(uri), row, caller::ensureActive)
                    }
                    if (selected != null) {
                        val hits = searchWorkspaceSymbols(mapOf(uri to selected.index), query, limit - size) {
                            selected.content
                        }
                        addAll(hits)
                        if (size >= limit) break
                    }
                }
            }
        }
        if (!deliveryInputsCurrent(::identitiesCurrent) {
                checks.all { it() } && hidden == index.hiddenRows()
            }
        ) {
            changed()
        }
        return result.getOrThrow()
    }

    private class Row(val content: String, val index: SymbolIndex)

    private suspend fun select(
        source: SnapshotSource,
        row: SymbolIndexingService.Prepared,
        checkCancelled: () -> Unit,
    ): Row? {
        val open = source.buffer?.snapshot?.content
        val observed = row.sourceText.read(source.uri, open, allowReplacement = true, checkCancelled)
        val text = when (observed) {
            IndexedTextRead.Missing -> {
                checks.add {
                    checkCancelled()
                    !IndexedSourceText.isPresent(source.uri, open) && source.isCurrent()
                }
                return null
            }
            IndexedTextRead.Changed -> changed()
            is IndexedSourceText -> observed
        }
        checks.add { text.matches(source.uri, open, checkCancelled) && source.isCurrent() }
        val inputs = row.inputs
        if (text === row.sourceText && inputs != null) {
            val current = {
                inputs.isCurrent(source, checkCancelled) &&
                    inputs.producerCurrent(source) && source.workerCurrent() && source.isCurrent()
            }
            if (current()) {
                checks.add(current)
                return Row(text.content, row.index)
            }
        }
        return rebuild(source, text, checkCancelled)
    }

    /** The rebuild must use the text already read within the original row's budget. */
    private suspend fun rebuild(source: SnapshotSource, text: IndexedSourceText, checkCancelled: () -> Unit): Row? =
        when (val acquisition = compilation.acquireSymbolRow(source, text)) {
            is SnapshotAcquisition.Available -> {
                val held = acquisition.snapshot
                val physical = held.requireParseInputs()
                val current = {
                    held.sourceText == text.content && physical.isCurrent(source, checkCancelled) &&
                        acquisition.isCurrent() && source.workerCurrent() && source.isCurrent()
                }
                if (!current()) changed()
                checks.add(current)
                if (physical.ownsSymbolAst) {
                    Row(text.content, SymbolIndex().buildFromVisitor(held.parseResult.astModel))
                } else {
                    null
                }
            }
            SnapshotAcquisition.Missing, SnapshotAcquisition.Stale -> changed()
            is SnapshotAcquisition.Failure -> throw acquisition.cause
        }

    private fun changed(): Nothing = throw ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Workspace changed during symbol search", null),
    )
}
