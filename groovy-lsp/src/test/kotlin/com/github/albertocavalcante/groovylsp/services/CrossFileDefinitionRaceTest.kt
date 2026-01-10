package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for issue #749: Cross-file definition resolution race condition.
 *
 * The race condition occurs when:
 * 1. Two files are opened via didOpen, triggering async compilation
 * 2. A definition request happens before both files finish compiling
 * 3. The target file (Calculator.groovy) may not be indexed yet
 * 4. Resolution fails because the class isn't in the symbol index
 */
class CrossFileDefinitionRaceTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var service: GroovyTextDocumentService
    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        scope = CoroutineScope(Dispatchers.Default)
        service = GroovyTextDocumentService(
            coroutineScope = scope,
            compilationService = compilationService,
            options = GroovyTextDocumentServiceOptions(
                client = { null },
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        compilationService.clearCaches()
    }

    @Test
    fun `should resolve cross-file definition when both files opened via didOpen`() = runTest {
        // Arrange: Calculator.groovy defines com.example.Calculator
        val calculatorUri = "file:///test/com/example/Calculator.groovy"
        val calculatorContent = """
            package com.example
            class Calculator {
                Calculator(int x) {}
            }
        """.trimIndent()

        // Arrange: Main.groovy uses Calculator
        val mainUri = "file:///test/com/example/Main.groovy"
        val mainContent = """
            package com.example
            class Main {
                void run() {
                    new Calculator(10)
                }
            }
        """.trimIndent()

        // Act: Open both files via didOpen (simulating LSP client behavior)
        // This triggers async compilation for both files
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(calculatorUri, "groovy", 1, calculatorContent),
            ),
        )
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(mainUri, "groovy", 1, mainContent),
            ),
        )

        // Simulate a small delay (like E2E test waiting for ONE diagnostics notification)
        // This creates the race condition: definition request may happen before Calculator compiles
        delay(50)

        // Act: Request definition for "Calculator" in Main.groovy
        val params = DefinitionParams(
            TextDocumentIdentifier(mainUri),
            Position(3, 12), // "Calculator" in "new Calculator(10)"
        )

        val resultFuture = service.definition(params)
        val result = resultFuture.get()

        // Assert: Should resolve to Calculator.groovy
        // WITHOUT the fix, this test may fail intermittently (race condition)
        // WITH the fix, this test should always pass
        assertTrue(result.isLeft, "Result should be Left (List<Location>)")
        val locations = result.left
        assertEquals(1, locations.size, "Should find exactly one definition")
        assertTrue(
            locations[0].uri.contains("Calculator.groovy"),
            "Definition should point to Calculator.groovy, but got: ${locations[0].uri}",
        )
    }

    @Test
    fun `should handle rapid didOpen followed by definition request`() = runTest {
        // This test simulates the exact E2E scenario where files are opened
        // in quick succession and definition is requested immediately after

        val calculatorUri = "file:///test/Calculator.groovy"
        val calculatorContent = """
            class Calculator {
                int add(int a, int b) { return a + b }
            }
        """.trimIndent()

        val mainUri = "file:///test/Main.groovy"
        val mainContent = """
            class Main {
                void test() {
                    Calculator calc = new Calculator()
                }
            }
        """.trimIndent()

        // Open files in quick succession
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(calculatorUri, "groovy", 1, calculatorContent)))
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mainUri, "groovy", 1, mainContent)))

        // Immediately request definition (no delay - worst case race condition)
        val params = DefinitionParams(
            TextDocumentIdentifier(mainUri),
            Position(2, 30), // "Calculator" in "new Calculator()"
        )

        val resultFuture = service.definition(params)
        val result = resultFuture.get()

        // Should still resolve correctly
        // The fix ensures all open documents are compiled before resolution
        assertTrue(result.isLeft, "Result should be Left (List<Location>) but was Right")
        val locations = result.left
        assertTrue(locations.isNotEmpty(), "Should find at least one definition location (Location)")
        assertTrue(
            locations[0].uri.contains("Calculator.groovy"),
            "Definition should point to Calculator.groovy, but got: ${locations[0].uri}",
        )
    }

    @Test
    fun `should handle concurrent didOpen and definition requests`() = runTest {
        // Test concurrent access: multiple files opening and definition requests happening
        // This tests thread safety of the fix

        val file1Uri = "file:///test/File1.groovy"
        val file1Content = "class File1 { }"

        val file2Uri = "file:///test/File2.groovy"
        val file2Content = "class File2 { File1 f }"

        // Launch concurrent operations
        val job1 = launch {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(file1Uri, "groovy", 1, file1Content)))
        }
        val job2 = launch {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(file2Uri, "groovy", 1, file2Content)))
        }
        val job3 = launch {
            delay(10) // Small delay to increase chance of race
            val params = DefinitionParams(
                TextDocumentIdentifier(file2Uri),
                Position(0, 14), // "File1" reference in "class File2 { File1 f }" - char 14 is 'F' of File1
            )
            val result = service.definition(params).get()

            // Assert that the definition request succeeded and returned a valid result
            // The key test here is thread safety - no exception should be thrown
            assertTrue(result.isLeft, "Result should be Left (List<Location>) not Right")
            val locations = result.left
            assertNotNull(locations, "Locations list should not be null")
            // Note: We don't assert the specific resolution here because type resolution
            // of field declarations is complex and not the focus of this race condition fix
        }

        // Wait for all to complete
        job1.join()
        job2.join()
        job3.join()
    }

    @Test
    fun `should resolve definition when files opened in reverse dependency order`() = runTest {
        // Open Main.groovy first (depends on Calculator)
        // Then open Calculator.groovy
        // Definition should still work

        val mainUri = "file:///test/Main.groovy"
        val mainContent = """
            class Main {
                void test() {
                    Calculator calc = new Calculator()
                }
            }
        """.trimIndent()

        val calculatorUri = "file:///test/Calculator.groovy"
        val calculatorContent = """
            class Calculator {
                int add(int a, int b) { return a + b }
            }
        """.trimIndent()

        // Open Main first (reverse order)
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mainUri, "groovy", 1, mainContent)))
        delay(50)

        // Open Calculator second
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(calculatorUri, "groovy", 1, calculatorContent)))
        delay(50)

        // Request definition in Main
        val params = DefinitionParams(
            TextDocumentIdentifier(mainUri),
            Position(2, 30), // "Calculator" in "new Calculator()"
        )

        val resultFuture = service.definition(params)
        val result = resultFuture.get()

        // Should resolve to Calculator.groovy despite reverse opening order
        assertTrue(result.isLeft, "Result should be Left (List<Location>)")
        val locations = result.left
        assertTrue(locations.isNotEmpty(), "Should find at least one definition")
        assertTrue(
            locations[0].uri.contains("Calculator.groovy"),
            "Definition should point to Calculator.groovy, but got: ${locations[0].uri}",
        )
    }

    @Test
    fun `should handle multiple cross-file references in single file`() = runTest {
        // Main.groovy references Calculator, Logger, Utils - all in memory

        val calculatorUri = "file:///test/Calculator.groovy"
        val calculatorContent = "class Calculator { int add(int a, int b) { return a + b } }"

        val loggerUri = "file:///test/Logger.groovy"
        val loggerContent = "class Logger { void log(String msg) { } }"

        val utilsUri = "file:///test/Utils.groovy"
        val utilsContent = "class Utils { static String format(String s) { return s } }"

        val mainUri = "file:///test/Main.groovy"
        val mainContent = """
            class Main {
                void run() {
                    Calculator calc = new Calculator()
                    Logger logger = new Logger()
                    String formatted = Utils.format("test")
                }
            }
        """.trimIndent()

        // Open all files
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(calculatorUri, "groovy", 1, calculatorContent)))
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(loggerUri, "groovy", 1, loggerContent)))
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(utilsUri, "groovy", 1, utilsContent)))
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mainUri, "groovy", 1, mainContent)))
        delay(100)

        // Test definition for Calculator
        val calcParams = DefinitionParams(TextDocumentIdentifier(mainUri), Position(2, 30)) // "Calculator" in new
        val calcResult = service.definition(calcParams).get()
        assertTrue(calcResult.isLeft)
        assertTrue(calcResult.left.isNotEmpty())
        assertTrue(calcResult.left[0].uri.contains("Calculator.groovy"))

        // Test definition for Logger
        val loggerParams = DefinitionParams(TextDocumentIdentifier(mainUri), Position(3, 24)) // "Logger" in new
        val loggerResult = service.definition(loggerParams).get()
        assertTrue(loggerResult.isLeft)
        assertTrue(loggerResult.left.isNotEmpty())
        assertTrue(loggerResult.left[0].uri.contains("Logger.groovy"))

        // Test definition for Utils
        val utilsParams = DefinitionParams(TextDocumentIdentifier(mainUri), Position(4, 40)) // "Utils" in Utils.format
        val utilsResult = service.definition(utilsParams).get()
        assertTrue(utilsResult.isLeft)
        assertTrue(utilsResult.left.isNotEmpty())
        assertTrue(utilsResult.left[0].uri.contains("Utils.groovy"))
    }

    @Test
    fun `should resolve definition after file content changes`() = runTest {
        // Open files, change Calculator.groovy, request definition in Main.groovy

        val calculatorUri = "file:///test/Calculator.groovy"
        val initialContent = """
            class Calculator {
                int add(int a, int b) { return a + b }
            }
        """.trimIndent()

        val mainUri = "file:///test/Main.groovy"
        val mainContent = """
            class Main {
                void test() {
                    Calculator calc = new Calculator()
                }
            }
        """.trimIndent()

        // Open both files
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(calculatorUri, "groovy", 1, initialContent)))
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mainUri, "groovy", 1, mainContent)))
        delay(100)

        // Change Calculator content
        val updatedContent = """
            class Calculator {
                int add(int a, int b) { return a + b }
                int subtract(int a, int b) { return a - b }
            }
        """.trimIndent()

        service.didChange(
            DidChangeTextDocumentParams().apply {
                textDocument = org.eclipse.lsp4j.VersionedTextDocumentIdentifier(calculatorUri, 2)
                contentChanges = listOf(
                    org.eclipse.lsp4j.TextDocumentContentChangeEvent().apply {
                        text = updatedContent
                    },
                )
            },
        )
        delay(100)

        // Request definition in Main - should still work after change
        val params = DefinitionParams(
            TextDocumentIdentifier(mainUri),
            Position(2, 30), // "Calculator" in "new Calculator()"
        )

        val resultFuture = service.definition(params)
        val result = resultFuture.get()

        assertTrue(result.isLeft, "Result should be Left (List<Location>)")
        val locations = result.left
        assertTrue(locations.isNotEmpty(), "Should find definition after content change")
        assertTrue(
            locations[0].uri.contains("Calculator.groovy"),
            "Definition should point to Calculator.groovy",
        )
    }
}
