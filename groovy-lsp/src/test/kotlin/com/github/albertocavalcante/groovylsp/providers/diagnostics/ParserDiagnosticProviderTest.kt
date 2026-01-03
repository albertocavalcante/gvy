package com.github.albertocavalcante.groovylsp.providers.diagnostics

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserDiagnosticProviderTest {

    @Test
    fun `should return empty list when no parser diagnostics`() = runBlocking {
        val compilationService = mockk<GroovyCompilationService>()
        val uri = URI.create("file:///test.groovy")
        every { compilationService.getDiagnostics(uri) } returns emptyList()

        val provider = ParserDiagnosticProvider(compilationService)
        val diagnostics = provider.provideDiagnostics(uri, "println 'hello'").toList()

        assertTrue(diagnostics.isEmpty())
        verify { compilationService.getDiagnostics(uri) }
    }

    @Test
    fun `should emit parser diagnostics from compilation service`() = runBlocking {
        val compilationService = mockk<GroovyCompilationService>()
        val uri = URI.create("file:///test.groovy")

        val expectedDiagnostic = Diagnostic(
            Range(Position(0, 0), Position(0, 5)),
            "Syntax error: unexpected token",
            DiagnosticSeverity.Error,
            "groovy-parser",
        )

        every { compilationService.getDiagnostics(uri) } returns listOf(expectedDiagnostic)

        val provider = ParserDiagnosticProvider(compilationService)
        val diagnostics = provider.provideDiagnostics(uri, "bad syntax here").toList()

        assertEquals(1, diagnostics.size)
        assertEquals(expectedDiagnostic, diagnostics.first())
        verify { compilationService.getDiagnostics(uri) }
    }

    @Test
    fun `should emit multiple parser diagnostics`() = runBlocking {
        val compilationService = mockk<GroovyCompilationService>()
        val uri = URI.create("file:///test.groovy")

        val diagnostic1 = Diagnostic(
            Range(Position(0, 0), Position(0, 5)),
            "Error 1",
            DiagnosticSeverity.Error,
            "groovy-parser",
        )
        val diagnostic2 = Diagnostic(
            Range(Position(1, 0), Position(1, 10)),
            "Error 2",
            DiagnosticSeverity.Error,
            "groovy-parser",
        )

        every { compilationService.getDiagnostics(uri) } returns listOf(diagnostic1, diagnostic2)

        val provider = ParserDiagnosticProvider(compilationService)
        val diagnostics = provider.provideDiagnostics(uri, "code with errors").toList()

        assertEquals(2, diagnostics.size)
        assertEquals(diagnostic1, diagnostics[0])
        assertEquals(diagnostic2, diagnostics[1])
    }

    @Test
    fun `should handle exceptions gracefully and return empty list`() = runBlocking {
        val compilationService = mockk<GroovyCompilationService>()
        val uri = URI.create("file:///test.groovy")

        every { compilationService.getDiagnostics(uri) } throws RuntimeException("Test exception")

        val provider = ParserDiagnosticProvider(compilationService)
        val diagnostics = provider.provideDiagnostics(uri, "some code").toList()

        assertTrue(diagnostics.isEmpty(), "Should return empty list on exception")
    }

    @Test
    fun `should have correct provider metadata`() {
        val compilationService = mockk<GroovyCompilationService>()
        val provider = ParserDiagnosticProvider(compilationService)

        assertEquals("parser", provider.id)
        assertTrue(provider.enabledByDefault)
    }
}
