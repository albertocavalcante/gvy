// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.folding

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import org.eclipse.lsp4j.FoldingRange
import java.net.URI

/** Legacy cached-query API; public requests use a captured-document boundary. */
class FoldingRangeProvider(private val compilationService: GroovyCompilationService) {
    fun provideFoldingRanges(uri: URI): List<FoldingRange> {
        val snapshot = compilationService.getCompiledSnapshot(uri) ?: return emptyList()
        return buildFoldingRanges(uri, snapshot.sourceText, snapshot.parseResult.astModel) {}
    }
}
