// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.imports.ImportOrganizer
import com.github.albertocavalcante.gvy.gls.providers.imports.UnusedImportFacts
import com.github.albertocavalcante.nativeapi.ParseResult
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind

/**
 * The `source.organizeImports` action, for the Organize Imports command and `editor.codeActionsOnSave`.
 * Thin wiring over [ImportOrganizer].
 *
 * Computed from the compiled snapshot of exactly the current text and offered only when it changes the text. When no
 * such snapshot exists yet there is no action, never an edit computed from stale facts. A file with syntax errors
 * is still organized, but without removing imports: unusedness is unprovable on a broken AST.
 */
internal class OrganizeImportsAction(private val compilationService: GroovyCompilationService) {

    fun createAction(request: ImportActionRequest): CodeAction? {
        val snapshot = compilationService.getCompiledSnapshot(request.uri)
            ?.takeIf { it.sourceText == request.content } ?: return null
        return createAction(request, snapshot.parseResult)
    }

    internal fun createAction(request: ImportActionRequest, parseResult: ParseResult): CodeAction? {
        val edits = ImportOrganizer.organize(request.content, UnusedImportFacts.from(parseResult))
        if (edits.isEmpty()) return null
        return CodeAction("Organize imports").apply {
            kind = CodeActionKind.SourceOrganizeImports
            edit = request.versionedEdit(edits)
        }
    }
}
