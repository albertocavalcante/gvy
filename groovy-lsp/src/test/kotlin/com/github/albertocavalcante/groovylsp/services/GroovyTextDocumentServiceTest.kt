package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.compilation.ParseResultAccessor
import com.github.albertocavalcante.groovylsp.compilation.SymbolIndexingService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class GroovyTextDocumentServiceTest {

    @Test
    fun `test definition returns empty list when compilation fails or is missing`() = runBlocking {
        // Mock dependencies
        val compilationService = mockk<GroovyCompilationService>()
        val parseResultAccessor = mockk<ParseResultAccessor>()
        val symbolIndexer = mockk<SymbolIndexingService>()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        // Create service under test
        val service = GroovyTextDocumentService(
            coroutineScope = scope,
            compilationService = compilationService,
            client = { null },
        )

        val uri = "file:///test/Test.groovy"
        val params = DefinitionParams(
            TextDocumentIdentifier(uri),
            Position(0, 0),
        )

        // Mock ensureCompiled to return null (simulating missing compilation)
        coEvery { compilationService.ensureCompiled(any()) } returns null

        // Execute
        val resultFuture = service.definition(params)
        val result = resultFuture.get()

        // Verify
        assertTrue(result.isLeft, "Result should be Left (List<Location>)")
        assertTrue(result.left.isEmpty(), "Result should be empty list when compilation is missing")
    }
}
