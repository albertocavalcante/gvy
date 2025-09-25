package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI

class DebugTest {

    @Test
    fun `debug basic compilation`() = runBlocking {
        val service = GroovyCompilationService()

        val simpleContent = """
            class TestClass {
                String name
                void greet() {
                    println "Hello"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val result = service.compile(uri, simpleContent)

        println("=== DEBUG RESULT ===")
        println("Success: ${result.isSuccess}")
        println("AST: ${result.ast}")
        println("Diagnostics count: ${result.diagnostics.size}")
        result.diagnostics.forEach { diag ->
            println("  - ${diag.severity}: ${diag.message}")
        }
    }

    @Test
    fun `debug invalid compilation`() = runBlocking {
        val service = GroovyCompilationService()

        val invalidContent = """
            package test

            class TestClass {
                // Missing opening brace
                void badMethod( {
                    println "This has syntax errors"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test/BadClass.groovy")
        val result = service.compile(uri, invalidContent)

        println("=== DEBUG INVALID RESULT ===")
        println("Success: ${result.isSuccess}")
        println("AST: ${result.ast}")
        println("Diagnostics count: ${result.diagnostics.size}")
        result.diagnostics.forEach { diag ->
            println("  - ${diag.severity}: ${diag.message} at ${diag.range}")
        }

        // Test assertions like in the real test
        println("=== TEST ASSERTIONS ===")
        println("assertFalse(result.isSuccess) would be: ${!result.isSuccess}")
        println("assertTrue(result.diagnostics.isNotEmpty()) would be: ${result.diagnostics.isNotEmpty()}")
    }
}
