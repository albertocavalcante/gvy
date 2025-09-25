package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI

class PositionDebugTest {

    @Test
    fun `debug position information`() = runBlocking {
        val service = GroovyCompilationService()

        val contentWithError = """
            class TestClass {
                void method( {
                    println "error on line 3"
                }
            }
        """.trimIndent()

        println("=== CODE CONTENT ===")
        contentWithError.lines().forEachIndexed { index, line ->
            println("Line $index: $line")
        }

        val uri = URI.create("file:///test/PositionTest.groovy")
        val result = service.compile(uri, contentWithError)

        println("=== DIAGNOSTIC POSITION ===")
        if (result.diagnostics.isNotEmpty()) {
            val diagnostic = result.diagnostics.first()
            println("Message: ${diagnostic.message}")
            println("Line (0-indexed): ${diagnostic.range.start.line}")
            println("Line (1-indexed): ${diagnostic.range.start.line + 1}")
            println("Column: ${diagnostic.range.start.character}")

            println("Expected line (0-indexed): 2")
            println("Matches expected: ${diagnostic.range.start.line == 2}")
        } else {
            println("No diagnostics found!")
        }
    }
}
