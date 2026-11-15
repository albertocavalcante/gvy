// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.google.gson.JsonObject
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic

internal object AnalysisRemedyAction {
    fun create(documentUri: String, diagnostics: List<Diagnostic>, only: List<String>?): List<CodeAction> {
        if (!allowsKind(CodeActionKind.QuickFix, only)) return emptyList()
        val analysis = diagnostics.filter {
            it.code?.left in setOf("gvy.degraded", "gvy.providerFailure", "gvy.sourceModel")
        }
        if (analysis.isEmpty()) return emptyList()
        return buildList {
            add(action("Show analysis details", "groovy.showAnalysisDetails", analysis, listOf(documentUri)))
            if (analysis.any { it.code?.left in setOf("gvy.degraded", "gvy.sourceModel") }) {
                add(action("Refresh Dependencies", "groovy.retryDependencyResolution", analysis))
            }
            analysis.mapNotNull(::configurationUri).distinct().forEach { uri ->
                add(action("Open configuration", "groovy.openAnalysisConfiguration", analysis, listOf(uri)))
            }
        }
    }

    private fun configurationUri(diagnostic: Diagnostic): String? = when (val data = diagnostic.data) {
        is JsonObject -> data.get("configurationUri")?.takeIf { it.isJsonPrimitive }?.asString
        is Map<*, *> -> data["configurationUri"] as? String
        else -> null
    }

    private fun action(title: String, commandId: String, diagnostics: List<Diagnostic>, args: List<Any> = emptyList()) =
        CodeAction(title).apply {
            kind = CodeActionKind.QuickFix
            this.diagnostics = diagnostics
            command = Command(title, commandId, args)
        }
}

internal fun allowsKind(kind: String, only: List<String>?): Boolean =
    only == null || only.any { it.isEmpty() || kind == it || kind.startsWith("$it.") }
