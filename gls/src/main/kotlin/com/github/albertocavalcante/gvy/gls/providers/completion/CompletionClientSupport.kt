// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import org.eclipse.lsp4j.CompletionItem

/**
 * Adapts completion items to what the client can render. A client without LSP 3.17 `labelDetailsSupport`
 * ignores `labelDetails`, so items told apart only by a label-details hint (for example the package of
 * `choose(Date value)`) would look identical. For such a client the hint moves into `detail`; `label` is
 * never changed because clients sort and filter on it.
 */
internal fun List<CompletionItem>.forClient(labelDetailsSupport: Boolean): List<CompletionItem> {
    if (labelDetailsSupport) return this
    forEach { item ->
        val hint = item.labelDetails?.description ?: return@forEach
        item.detail = item.detail?.let { "$it ($hint)" } ?: hint
        item.labelDetails = null
    }
    return this
}
