package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroovyWorkspaceServiceTest {

    @Test
    fun `blank workspace symbol query returns empty result`() {
        val service = GroovyWorkspaceService(GroovyCompilationService())

        val either = service.symbol(WorkspaceSymbolParams("   ")).get()

        assertTrue(either.isLeft)
        assertTrue(either.left.isEmpty())
    }
}
