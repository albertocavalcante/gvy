// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.imports.TextReplacement
import com.github.albertocavalcante.gvy.gls.providers.imports.UnusedImport
import com.github.albertocavalcante.gvy.gls.providers.imports.UnusedImportFacts
import com.github.albertocavalcante.gvy.gls.providers.imports.UnusedImportRemoval
import com.github.albertocavalcante.nativeapi.ParseResult
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic

/**
 * Quick fixes for gvy's own `unused-import` diagnostic: "Remove unused import" per diagnostic, and
 * "Remove all unused imports" when two or more can be removed. Thin wiring over [UnusedImportRemoval].
 *
 * Offered only from a compiled snapshot of exactly the current text; otherwise no action rather than a stale edit.
 */
internal class UnusedImportFixAction(private val compilationService: GroovyCompilationService) {

    fun createActions(request: ImportActionRequest): List<CodeAction> {
        val snapshot = compilationService.getCompiledSnapshot(request.uri)
            ?.takeIf { it.sourceText == request.content } ?: return emptyList()
        return createActions(request, snapshot.parseResult)
    }

    internal fun createActions(request: ImportActionRequest, parseResult: ParseResult): List<CodeAction> {
        val reported = request.diagnostics.filter { it.source == GVY_SOURCE && it.code?.left == UNUSED_IMPORT_CODE }
        if (reported.isEmpty()) return emptyList()
        val unused = UnusedImportFacts.from(parseResult)

        val single = reported.mapNotNull { diagnostic -> removeOne(request, unused, diagnostic) }
        val all = removeAll(request, unused, reported)
        return single + listOfNotNull(all)
    }

    private fun removeOne(
        request: ImportActionRequest,
        unused: List<UnusedImport>,
        diagnostic: Diagnostic,
    ): CodeAction? {
        val target = unused.firstOrNull { it.line == diagnostic.range.start.line } ?: return null
        val edits = UnusedImportRemoval.plan(request.content, listOf(target))
        return action("Remove unused import", request, edits, listOf(diagnostic))
    }

    private fun removeAll(
        request: ImportActionRequest,
        unused: List<UnusedImport>,
        reported: List<Diagnostic>,
    ): CodeAction? {
        val removable = UnusedImportRemoval.removableLines(request.content, unused)
        if (removable.size < 2) return null
        val edits = UnusedImportRemoval.plan(request.content, unused)
        return action("Remove all unused imports", request, edits, reported)
    }

    private fun action(
        title: String,
        request: ImportActionRequest,
        edits: List<TextReplacement>,
        diagnostics: List<Diagnostic>,
    ): CodeAction? {
        if (edits.isEmpty()) return null
        return CodeAction(title).apply {
            kind = CodeActionKind.QuickFix
            this.diagnostics = diagnostics
            edit = request.versionedEdit(edits)
        }
    }

    private companion object {
        const val UNUSED_IMPORT_CODE = "unused-import"
        const val GVY_SOURCE = "Groovy"
    }
}
