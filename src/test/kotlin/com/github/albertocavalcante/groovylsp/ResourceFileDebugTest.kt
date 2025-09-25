package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import java.net.URI

class ResourceFileDebugTest {

    @Test
    fun `debug syntax error resource file`() = runBlocking {
        val service = GroovyCompilationService()

        // Load the SyntaxError.groovy test resource
        val syntaxErrorContent = this::class.java.classLoader
            .getResource("SyntaxError.groovy")
            ?.readText()
            ?: error("Could not load SyntaxError.groovy test resource")

        println("=== SYNTAX ERROR CONTENT ===")
        println(syntaxErrorContent)

        val uri = URI.create("file:///test/SyntaxError.groovy")
        val result = service.compile(uri, syntaxErrorContent)

        println("=== COMPILATION RESULT ===")
        println("Success: ${result.isSuccess}")
        println("Diagnostics count: ${result.diagnostics.size}")
        println("AST: ${result.ast != null}")

        result.diagnostics.forEachIndexed { index, diag ->
            println("Diagnostic $index: ${diag.severity} - ${diag.message} at line ${diag.range.start.line + 1}")
        }

        // Test expectations
        println("=== TEST EXPECTATIONS ===")
        println("assertFalse(result.isSuccess) = ${!result.isSuccess}")
        println("assertTrue(result.diagnostics.isNotEmpty()) = ${result.diagnostics.isNotEmpty()}")

        val errors = result.diagnostics.filter { it.severity == DiagnosticSeverity.Error }
        println("assertTrue(errors.size > 1) = ${errors.size > 1} (actual: ${errors.size})")
    }
}
