package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import io.mockk.coEvery
import io.mockk.every
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
        val compilationService = mockk<GroovyCompilationService>(relaxed = true)
        val workspaceManager = mockk<WorkspaceManager>(relaxed = true)
        val scope = CoroutineScope(Dispatchers.Unconfined)

        // Mock workspaceManager property which is accessed by CompilationEnsurer
        every { compilationService.workspaceManager } returns workspaceManager

        // Create service under test
        val service = GroovyTextDocumentService(
            coroutineScope = scope,
            compilationService = compilationService,
            options = GroovyTextDocumentServiceOptions(
                client = { null },
            ),
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
