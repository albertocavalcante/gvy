// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.jenkins.completion.JenkinsSourceContext
import com.github.albertocavalcante.nativeapi.ParseResult

/** Derived source facts owned by one cache entry; contains no request, mode or profile state. */
internal class CompiledSourceView(val sourceText: String, val parseResult: ParseResult) {
    val jenkinsContext: JenkinsSourceContext by lazy { JenkinsSourceContext(sourceText) }
    val sourceIndex: TextIndex by lazy { TextIndex(sourceText) }
}
