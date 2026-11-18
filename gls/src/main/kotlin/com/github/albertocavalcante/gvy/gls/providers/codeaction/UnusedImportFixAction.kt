// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.imports.TextReplacement
import com.github.albertocavalcante.gvy.gls.providers.imports.UnusedImport
import com.github.albertocavalcante.gvy.gls.providers.imports.UnusedImportFacts
import com.github.albertocavalcante.gvy.gls.providers.imports.UnusedImportRemoval
import com.github.albertocavalcante.gvy.source.Utf16LineIndex
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI

/** What the code action request is about: one document at one version, with the diagnostics the client sent. */
internal data class ImportActionRequest(
    val uri: URI,
    val version: Int?,
    val content: String,
    val diagnostics: List<Diagnostic>,
)

/**
 * Quick fixes for gvy's own `unused-import` diagnostic: "Remove unused import" per diagnostic, and
 * "Remove all unused imports" when two or more can be removed. Thin wiring over [UnusedImportRemoval].
 *
 * Offered only from a compiled snapshot of exactly the current text; otherwise no action rather than a stale edit.
 */
internal class UnusedImportFixAction(private val compilationService: GroovyCompilationService) {

    fun createActions(request: ImportActionRequest): List<CodeAction> {
        val reported = request.diagnostics.filter { it.code?.left == UNUSED_IMPORT_CODE }
        if (reported.isEmpty()) return emptyList()
        val unused = unusedImports(request) ?: return emptyList()

        val single = reported.mapNotNull { diagnostic -> removeOne(request, unused, diagnostic) }
        val all = removeAll(request, unused, reported)
        return single + listOfNotNull(all)
    }

    private fun unusedImports(request: ImportActionRequest): List<UnusedImport>? {
        val snapshot = compilationService.getCompiledSnapshot(request.uri)
            ?.takeIf { it.sourceText == request.content }
        return snapshot?.let { UnusedImportFacts.from(it.parseResult) }
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
        val edits = UnusedImportRemoval.plan(request.content, unused)
        return if (edits.size < 2) null else action("Remove all unused imports", request, edits, reported)
    }

    private fun action(
        title: String,
        request: ImportActionRequest,
        edits: List<TextReplacement>,
        diagnostics: List<Diagnostic>,
    ): CodeAction? {
        if (edits.isEmpty()) return null
        val index = Utf16LineIndex(request.content)
        val textEdits = edits.map { TextEdit(Range(position(index, it.start), position(index, it.end)), it.newText) }
        val document = VersionedTextDocumentIdentifier(request.uri.toString(), request.version)
        return CodeAction(title).apply {
            kind = CodeActionKind.QuickFix
            this.diagnostics = diagnostics
            edit = WorkspaceEdit(listOf(Either.forLeft(TextDocumentEdit(document, textEdits))))
        }
    }

    private fun position(index: Utf16LineIndex, offset: Int): Position {
        val line = index.lineAt(offset)
        return Position(line, offset - index.lineStart(line))
    }

    private companion object {
        const val UNUSED_IMPORT_CODE = "unused-import"
    }
}
