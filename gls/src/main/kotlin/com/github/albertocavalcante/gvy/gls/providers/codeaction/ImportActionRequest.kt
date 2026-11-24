// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.providers.imports.TextReplacement
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.source.Utf16LineIndex
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
    val version: DocumentVersion?,
    val content: String,
    val diagnostics: List<Diagnostic>,
)

/** The edits as one versioned document edit, so the client rejects them if the buffer moved on. */
internal fun ImportActionRequest.versionedEdit(edits: List<TextReplacement>): WorkspaceEdit {
    val index = Utf16LineIndex(content)

    fun position(offset: Int): Position {
        val line = index.lineAt(offset)
        return Position(line, offset - index.lineStart(line))
    }

    val textEdits = edits.map { TextEdit(Range(position(it.start), position(it.end)), it.newText) }
    val document = VersionedTextDocumentIdentifier(uri.toString(), version?.value)
    return WorkspaceEdit(listOf(Either.forLeft(TextDocumentEdit(document, textEdits))))
}
