// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticCodeDescription
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier

private val diagnosticJson = GsonBuilder().serializeNulls().create()

/** Detach mutable protocol objects before dispatch, including diagnostic metadata returned with actions. */
internal fun copyCodeActionParams(params: CodeActionParams): CodeActionParams {
    fun copyRange(range: Range): Range = Range(
        Position(range.start.line, range.start.character),
        Position(range.end.line, range.end.character),
    )
    val diagnostics = params.context.diagnostics.map { original ->
        Diagnostic(copyRange(original.range), original.message).apply {
            severity = original.severity
            code = original.code
            source = original.source
            tags = original.tags?.toList()
            codeDescription = original.codeDescription?.let { DiagnosticCodeDescription(it.href) }
            relatedInformation = original.relatedInformation?.map {
                DiagnosticRelatedInformation(Location(it.location.uri, copyRange(it.location.range)), it.message)
            }
            data = when (val value = original.data) {
                null -> null
                is JsonElement -> value.deepCopy()
                else -> diagnosticJson.toJsonTree(value)
            }
        }
    }
    return CodeActionParams(
        TextDocumentIdentifier(params.textDocument.uri),
        copyRange(params.range),
        CodeActionContext(diagnostics).apply { only = params.context.only?.toList() },
    )
}
