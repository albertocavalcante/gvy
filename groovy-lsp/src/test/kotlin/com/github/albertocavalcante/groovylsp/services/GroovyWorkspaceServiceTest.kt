package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroovyWorkspaceServiceTest {

    private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Test
    fun `blank workspace symbol query returns empty result`() {
        val service = GroovyWorkspaceService(GroovyCompilationService(), testScope)

        val either = service.symbol(WorkspaceSymbolParams("   ")).get()

        assertTrue(either.isLeft)
        assertTrue(either.left.isEmpty())
    }
}
