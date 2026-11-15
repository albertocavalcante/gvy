// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.dsl.completion.TemplateCompletionItem
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.InsertTextFormat

/**
 * Adapts completion items to what the client can render. A client without LSP 3.17 `labelDetailsSupport`
 * ignores `labelDetails`, so items told apart only by a label-details hint (for example the package of
 * `choose(Date value)`) would look identical. For such a client the hint moves into `detail`; `label` is
 * never changed because clients sort and filter on it.
 */
internal fun List<CompletionItem>.forClient(
    labelDetailsSupport: Boolean,
    snippetSupport: Boolean = true,
): List<CompletionItem> {
    if (!snippetSupport) forEach(::usePlainInsertion)
    if (labelDetailsSupport) return this
    forEach { item ->
        val hint = item.labelDetails?.description ?: return@forEach
        item.detail = item.detail?.let { "$it ($hint)" } ?: hint
        item.labelDetails = null
    }
    return this
}

private fun usePlainInsertion(item: CompletionItem) {
    if (item.insertTextFormat != InsertTextFormat.Snippet) return
    val plainText = (item as? TemplateCompletionItem)?.insertionTemplate?.plainText ?: item.label
    item.insertText = plainText
    item.insertTextFormat = InsertTextFormat.PlainText
    item.textEdit?.let { edit ->
        if (edit.isLeft) edit.left.newText = plainText else edit.right.newText = plainText
    }
    if (item.textEditText != null) item.textEditText = plainText
}
