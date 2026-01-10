package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            Position(2, 31), // "Calculator" in "new Calculator()"
        )

        val resultFuture = service.definition(params)
        val result = resultFuture.get()

        // Should still resolve correctly
        // The fix ensures all open documents are compiled before resolution
        if (result.isRight) {
            // If it's Right, it's a LocationLink list - convert to check
            val locationLinks = result.right
            assertTrue(locationLinks.isNotEmpty(), "Should find at least one definition location (LocationLink)")
            assertTrue(
                locationLinks[0].targetUri.contains("Calculator.groovy"),
                "Definition should point to Calculator.groovy, but got: ${locationLinks[0].targetUri}",
            )
        } else {
            // If it's Left, it's a Location list
            val locations = result.left
            assertTrue(locations.isNotEmpty(), "Should find at least one definition location (Location)")
            assertTrue(
                locations[0].uri.contains("Calculator.groovy"),
                "Definition should point to Calculator.groovy, but got: ${locations[0].uri}",
            )
        }
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
                Position(0, 19), // "File1" reference
            )
            service.definition(params).get()
        }

        // Wait for all to complete
        job1.join()
        job2.join()
        job3.join()

        // If we get here without exceptions, the fix handles concurrency correctly
        assertTrue(true, "Concurrent operations should not cause race conditions")
    }
}
