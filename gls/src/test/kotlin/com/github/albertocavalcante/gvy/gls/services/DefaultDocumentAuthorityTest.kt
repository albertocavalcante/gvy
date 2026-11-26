// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class DefaultDocumentAuthorityTest {
    enum class Route { FOLDING, SELECTION }

    @ParameterizedTest
    @CsvSource("FOLDING,false", "SELECTION,false", "FOLDING,true", "SELECTION,true")
    fun `default construction shares the document opened by the text service`(route: Route, options: Boolean) {
        val compilation = GroovyCompilationService()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val uri = "file:///default-authority.groovy"
        try {
            val service = if (options) {
                GroovyTextDocumentService(scope, compilation, GroovyTextDocumentServiceOptions())
            } else {
                GroovyTextDocumentService(scope, compilation)
            }
            val content = "class Example {\n  void run() {\n    println 1\n  }\n}\n"
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, content)))
            val document = TextDocumentIdentifier(uri)
            val count = when (route) {
                Route.FOLDING -> service.foldingRange(FoldingRangeRequestParams(document))
                    .get(10, TimeUnit.SECONDS).size
                Route.SELECTION -> service.selectionRange(SelectionRangeParams(document, listOf(Position(2, 4))))
                    .get(10, TimeUnit.SECONDS).orEmpty().size
            }
            assertTrue(count > 0)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
