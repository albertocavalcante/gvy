package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import java.net.URI

class CompilationDebugTest {

    @Test
    fun `debug compilation service with different inputs`() = runBlocking {
        val service = GroovyCompilationService()

        println("=== TESTING VALID CODE ===")
        val validContent = """
            package test
            class TestClass {
                String name
                void greet() {
                    println "Hello, " + name
                }
            }
        """.trimIndent()

        val validResult = service.compile(URI.create("file:///valid.groovy"), validContent)
        println("Valid - Success: ${validResult.isSuccess}")
        println("Valid - Diagnostics: ${validResult.diagnostics.size}")
        println("Valid - AST: ${validResult.ast != null}")

        println("\n=== TESTING INVALID CODE (missing paren) ===")
        val invalidContent = """
            package test
            class TestClass {
                void badMethod( {
                    println "This has syntax errors"
                }
            }
        """.trimIndent()

        val invalidResult = service.compile(URI.create("file:///invalid.groovy"), invalidContent)
        println("Invalid - Success: ${invalidResult.isSuccess}")
        println("Invalid - Diagnostics: ${invalidResult.diagnostics.size}")
        println("Invalid - AST: ${invalidResult.ast != null}")
        invalidResult.diagnostics.forEachIndexed { index, diag ->
            println("  Diagnostic $index: ${diag.severity} - ${diag.message}")
        }

        println("\n=== TESTING WORSE SYNTAX ERROR ===")
        val worseContent = """
            class BadClass {
                void method(
                    // missing closing paren and brace
        """.trimIndent()

        val worseResult = service.compile(URI.create("file:///worse.groovy"), worseContent)
        println("Worse - Success: ${worseResult.isSuccess}")
        println("Worse - Diagnostics: ${worseResult.diagnostics.size}")
        println("Worse - AST: ${worseResult.ast != null}")
        worseResult.diagnostics.forEachIndexed { index, diag ->
            println("  Diagnostic $index: ${diag.severity} - ${diag.message}")
        }

        // Show what the current test expects vs what we get
        println("\n=== TEST EXPECTATIONS ===")
        println("Test expects: !isSuccess = ${!invalidResult.isSuccess}") // should be true
        println("Test expects: diagnostics.isNotEmpty() = ${invalidResult.diagnostics.isNotEmpty()}") // should be true
        println(
            "Test expects: has error diagnostics = ${invalidResult.diagnostics.any {
                it.severity == DiagnosticSeverity.Error
            }}",
        ) // should be true
    }
}
