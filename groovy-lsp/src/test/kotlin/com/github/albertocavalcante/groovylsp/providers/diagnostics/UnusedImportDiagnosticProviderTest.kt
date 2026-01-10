package com.github.albertocavalcante.groovylsp.providers.diagnostics

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for UnusedImportDiagnosticProvider.
 * Tests the full flow from source code to diagnostics.
 */
class UnusedImportDiagnosticProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var provider: UnusedImportDiagnosticProvider
    private val uri = URI.create("file:///Test.groovy")

    @BeforeEach
    fun setup() {
        compilationService = GroovyCompilationService()
        provider = UnusedImportDiagnosticProvider(compilationService)
    }

    private fun compile(code: String) = runBlocking {
        compilationService.compile(uri, code)
    }

    @Test
    fun `should return diagnostics for unused imports`() = runBlocking {
        val code = """
            import java.util.ArrayList
            import java.util.HashMap

            ArrayList list = new ArrayList()
        """.trimIndent()

        compile(code)
        val diagnostics = provider.provideDiagnostics(uri, code).toList()

        assertEquals(1, diagnostics.size, "Should detect exactly one unused import")
        val diag = diagnostics.first()
        assertTrue(diag.tags.contains(DiagnosticTag.Unnecessary), "Should have Unnecessary tag")
        assertTrue(diag.message.contains("HashMap"), "Message should mention HashMap")
        assertEquals(DiagnosticSeverity.Hint, diag.severity, "Should be Hint severity")
    }

    @Test
    fun `should return empty list when all imports are used`() = runBlocking {
        val code = """
            import java.util.ArrayList

            ArrayList list = new ArrayList()
        """.trimIndent()

        compile(code)
        val diagnostics = provider.provideDiagnostics(uri, code).toList()

        assertTrue(diagnostics.isEmpty(), "Should have no diagnostics when all imports are used")
    }

    @Test
    fun `should return empty list when no imports exist`() = runBlocking {
        val code = """
            class Test {
                def x = 1
            }
        """.trimIndent()

        compile(code)
        val diagnostics = provider.provideDiagnostics(uri, code).toList()

        assertTrue(diagnostics.isEmpty(), "Should have no diagnostics when there are no imports")
    }

    @Test
    fun `diagnostic range should cover import line`() = runBlocking {
        val code = """
            import java.util.HashMap

            def x = 1
        """.trimIndent()

        compile(code)
        val diagnostics = provider.provideDiagnostics(uri, code).toList()

        assertEquals(1, diagnostics.size)
        val range = diagnostics.first().range
        // LSP is 0-indexed, import is on line 0
        assertEquals(0, range.start.line)
    }

    @Test
    fun `diagnostic should have correct code for quick fix integration`() = runBlocking {
        val code = """
            import java.util.HashMap

            def x = 1
        """.trimIndent()

        compile(code)
        val diagnostics = provider.provideDiagnostics(uri, code).toList()

        assertEquals(1, diagnostics.size)
        val diagCode = diagnostics.first().code
        assertEquals("unused-import", diagCode.left, "Diagnostic code should be 'unused-import'")
    }

    @Test
    fun `should detect multiple unused imports`() = runBlocking {
        val code = """
            import java.util.ArrayList
            import java.util.HashMap
            import java.util.LinkedList

            def x = 1
        """.trimIndent()

        compile(code)
        val diagnostics = provider.provideDiagnostics(uri, code).toList()

        assertEquals(3, diagnostics.size, "Should detect all three unused imports")
    }

    @Test
    fun `provider should be enabled by default`() {
        assertTrue(provider.enabledByDefault, "Provider should be enabled by default")
    }

    @Test
    fun `provider should have correct id`() {
        assertEquals("unused-imports", provider.id)
    }
}
