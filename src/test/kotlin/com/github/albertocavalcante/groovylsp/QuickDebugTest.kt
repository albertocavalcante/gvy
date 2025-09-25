package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.compilation.DiagnosticConverter
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.junit.jupiter.api.Test

class QuickDebugTest {

    @Test
    fun `debug error collector directly`() {
        val invalidContent = """
            package test
            class TestClass {
                void badMethod( {
                    println "This has syntax errors"
                }
            }
        """.trimIndent()

        val config = CompilerConfiguration()
        val classLoader = GroovyClassLoader()
        val compilationUnit = CompilationUnit(config, null, classLoader)

        val fileName = "test.groovy"
        val source = StringReaderSource(invalidContent, config)
        val sourceUnit = SourceUnit(fileName, source, config, classLoader, compilationUnit.errorCollector)
        compilationUnit.addSource(sourceUnit)

        println("=== BEFORE COMPILATION ===")
        println("Error collector errors: ${compilationUnit.errorCollector.errors?.size ?: "null"}")
        println("Error collector warnings: ${compilationUnit.errorCollector.warnings?.size ?: "null"}")

        try {
            compilationUnit.compile(Phases.CANONICALIZATION)
        } catch (e: Exception) {
            println("Compilation threw: ${e::class.simpleName} - ${e.message}")
        }

        println("=== AFTER COMPILATION ===")
        println("Error collector errors: ${compilationUnit.errorCollector.errors?.size ?: "null"}")
        println("Error collector warnings: ${compilationUnit.errorCollector.warnings?.size ?: "null"}")

        compilationUnit.errorCollector.errors?.forEachIndexed { index, error ->
            println("Error $index: ${error::class.simpleName} - $error")
        }

        // Test DiagnosticConverter
        val diagnostics = DiagnosticConverter.convertErrorCollector(compilationUnit.errorCollector)
        println("Converted diagnostics: ${diagnostics.size}")
        diagnostics.forEach { diag ->
            println("  - ${diag.severity}: ${diag.message}")
        }
    }
}
