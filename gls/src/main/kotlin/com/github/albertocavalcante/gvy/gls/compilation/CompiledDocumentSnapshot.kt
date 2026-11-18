// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseResult

/** Source and parse components captured from one cache entry. Consumers treat the AST as read-only. */
internal data class CompiledDocumentSnapshot(
    val sourceText: String,
    val parseResult: ParseResult,
    private val sourceView: CompiledSourceView? = null,
) {
    private val fallbackView by lazy { CompiledSourceView(sourceText, parseResult) }
    private val derivedSource: CompiledSourceView
        get() = sourceView?.takeIf { it.sourceText == sourceText && it.parseResult === parseResult } ?: fallbackView

    val jenkinsContext get() = derivedSource.jenkinsContext
    val sourceIndex get() = derivedSource.sourceIndex
}
