// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class GroovyTextDocumentServiceTest {

    @Test
    fun `test definition returns empty list when compilation fails or is missing`(@TempDir root: Path) {
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val uri = root.resolve("Missing.groovy").toUri().toString()
            val params = DefinitionParams(TextDocumentIdentifier(uri), Position(0, 0))
            val result = service.definition(params).get()
            assertTrue(result.isLeft, "Result should be Left (List<Location>)")
            assertTrue(result.left.isEmpty(), "Result should be empty list when compilation is missing")
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
