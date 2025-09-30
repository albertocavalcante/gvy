package com.github.albertocavalcante.groovylsp.providers.folding

import com.github.albertocavalcante.groovylsp.compilation.CentralizedDependencyManager
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoldingRangeProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var foldingRangeProvider: FoldingRangeProvider

    @BeforeEach
    fun setUp() {
        val dependencyManager = CentralizedDependencyManager()
        compilationService = GroovyCompilationService(dependencyManager)
        foldingRangeProvider = FoldingRangeProvider(compilationService)
    }

    @Test
    fun `should provide folding ranges for simple class`() {
        val sourceCode = """
            class TestClass {
                def method1() {
                    println "Hello"
                    println "World"
                }

                def method2() {
                    return 42
                }
            }
        """.trimIndent()

        val uri = createTestUri("TestClass.groovy")
        compileSource(uri, sourceCode)

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        // Should have ranges for class and methods
        assertTrue(ranges.isNotEmpty(), "Should provide folding ranges")

        // Find class folding range
        val classRange = ranges.find {
            it.kind == FoldingRangeKind.Region &&
                it.collapsedText?.contains("class TestClass") == true
        }
        assertTrue(classRange != null, "Should have class folding range")

        // Find method folding ranges
        val methodRanges = ranges.filter { it.collapsedText?.contains("method") == true }
        assertEquals(2, methodRanges.size, "Should have folding ranges for both methods")
    }

    @Test
    fun `should provide folding ranges for nested structures`() {
        val sourceCode = """
            class NestedClass {
                def complexMethod() {
                    if (true) {
                        println "if block"
                        for (i in 1..10) {
                            println i
                        }
                    } else {
                        println "else block"
                    }
                }
            }
        """.trimIndent()

        val uri = createTestUri("NestedClass.groovy")
        compileSource(uri, sourceCode)

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        assertTrue(ranges.isNotEmpty(), "Should provide folding ranges for nested structures")

        // Should have ranges for class, method, if/else blocks, and for loop
        val regionRanges = ranges.filter { it.kind == FoldingRangeKind.Region }
        assertTrue(regionRanges.size >= 4, "Should have multiple region ranges for nested structures")
    }

    @Test
    fun `should provide folding ranges for closures`() {
        val sourceCode = """
            class ClosureTest {
                def testClosures() {
                    def list = [1, 2, 3]
                    list.each { item ->
                        println item
                        println "processing"
                    }

                    list.collect {
                        it * 2
                    }
                }
            }
        """.trimIndent()

        val uri = createTestUri("ClosureTest.groovy")
        compileSource(uri, sourceCode)

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        assertTrue(ranges.isNotEmpty(), "Should provide folding ranges for closures")

        // Find closure folding ranges
        val closureRanges = ranges.filter { it.collapsedText?.contains("{") == true }
        assertTrue(closureRanges.isNotEmpty(), "Should have folding ranges for closures")
    }

    @Test
    fun `should provide folding ranges for imports`() {
        val sourceCode = """
            import java.util.List
            import java.util.Map
            import java.util.Set
            import java.util.HashMap
            import java.util.ArrayList

            class ImportTest {
                def test() {
                    println "test"
                }
            }
        """.trimIndent()

        val uri = createTestUri("ImportTest.groovy")
        compileSource(uri, sourceCode)

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        // Find import folding range
        val importRange = ranges.find { it.kind == FoldingRangeKind.Imports }
        assertTrue(importRange != null, "Should have import folding range for multiple imports")
    }

    @Test
    fun `should provide folding ranges for try-catch blocks`() {
        val sourceCode = """
            class ExceptionTest {
                def testException() {
                    try {
                        println "trying"
                        throw new Exception("test")
                    } catch (Exception e) {
                        println "caught"
                        e.printStackTrace()
                    } finally {
                        println "finally"
                    }
                }
            }
        """.trimIndent()

        val uri = createTestUri("ExceptionTest.groovy")
        compileSource(uri, sourceCode)

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        assertTrue(ranges.isNotEmpty(), "Should provide folding ranges for try-catch blocks")

        // Find try, catch, finally ranges
        val tryRange = ranges.find { it.collapsedText?.contains("try") == true }
        val catchRange = ranges.find { it.collapsedText?.contains("catch") == true }
        val finallyRange = ranges.find { it.collapsedText?.contains("finally") == true }

        assertTrue(tryRange != null, "Should have try block folding range")
        assertTrue(catchRange != null, "Should have catch block folding range")
        assertTrue(finallyRange != null, "Should have finally block folding range")
    }

    @Test
    fun `should handle single line structures gracefully`() {
        val sourceCode = """
            class SingleLine { def method() { return 42 } }
        """.trimIndent()

        val uri = createTestUri("SingleLine.groovy")
        compileSource(uri, sourceCode)

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        // Single line structures should not provide folding ranges
        assertTrue(ranges.isEmpty(), "Should not provide folding ranges for single line structures")
    }

    @Test
    fun `should handle empty files gracefully`() {
        val sourceCode = ""

        val uri = createTestUri("Empty.groovy")
        compileSource(uri, sourceCode)

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        assertTrue(ranges.isEmpty(), "Should handle empty files gracefully")
    }

    @Test
    fun `should handle compilation errors gracefully`() {
        val sourceCode = """
            class SyntaxError {
                def method() {
                    // Missing closing brace
        """.trimIndent()

        val uri = createTestUri("SyntaxError.groovy")
        // Don't compile to simulate compilation error

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        // Should return empty list for compilation errors
        assertTrue(ranges.isEmpty(), "Should handle compilation errors gracefully")
    }

    @Test
    fun `should not fold imports with less than 3 statements`() {
        val sourceCode = """
            import java.util.List
            import java.util.Map

            class SmallImport {
                def test() { }
            }
        """.trimIndent()

        val uri = createTestUri("SmallImport.groovy")
        compileSource(uri, sourceCode)

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        // Should not have import folding range for less than 3 imports
        val importRange = ranges.find { it.kind == FoldingRangeKind.Imports }
        assertTrue(importRange == null, "Should not fold imports with less than 3 statements")
    }

    @Test
    fun `should handle loop statements`() {
        val sourceCode = """
            class LoopTest {
                def testLoops() {
                    while (true) {
                        println "while loop"
                        break
                    }

                    for (i in 1..5) {
                        println "for loop: " + i
                    }
                }
            }
        """.trimIndent()

        val uri = createTestUri("LoopTest.groovy")
        compileSource(uri, sourceCode)

        val ranges = foldingRangeProvider.provideFoldingRanges(uri.toString())

        assertTrue(ranges.isNotEmpty(), "Should provide folding ranges for loop statements")

        // Find while and for loop ranges
        val whileRange = ranges.find { it.collapsedText?.contains("while") == true }
        val forRange = ranges.find { it.collapsedText?.contains("for") == true }

        assertTrue(whileRange != null, "Should have while loop folding range")
        assertTrue(forRange != null, "Should have for loop folding range")
    }

    private fun createTestUri(filename: String): URI = URI.create("file:///test/$filename")

    private fun compileSource(uri: URI, sourceCode: String) {
        runBlocking {
            compilationService.compile(uri, sourceCode)
        }
    }
}
