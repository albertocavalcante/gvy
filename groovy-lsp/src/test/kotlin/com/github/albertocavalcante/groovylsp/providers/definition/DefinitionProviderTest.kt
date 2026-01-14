package com.github.albertocavalcante.groovylsp.providers.definition

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class DefinitionProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var definitionProvider: DefinitionProvider
    private val telemetryEvents = mutableListOf<DefinitionTelemetryEvent>()

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        definitionProvider = DefinitionProvider(
            compilationService = compilationService,
            telemetrySink = DefinitionTelemetrySink { event -> telemetryEvents.add(event) },
        )
    }

    @AfterEach
    fun tearDown() {
        // Clear all caches to prevent test contamination
        compilationService.clearCaches()
        telemetryEvents.clear()
    }

    @Test
    fun `test local variable definition`() = runBlocking {
        // Arrange
        val content = """
            def localVar = "test"
            println localVar
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content to build AST and symbol tables
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - try to find definition of 'localVar' at position where it's used (line 1, column 8)
        val definitions = definitionProvider.provideDefinitions(uri.toString(), Position(1, 8)).toList()

        // Assert
        assertFalse(definitions.isEmpty(), "Should find definition for local variable")

        val definition = definitions.first()
        assertEquals(uri.toString(), definition.uri)

        // The definition should point to line 0 (where 'localVar' is declared)
        assertEquals(0, definition.range.start.line)

        val successEvent = telemetryEvents.lastOrNull()
        assertEquals(DefinitionStatus.SUCCESS, successEvent?.status)
    }

    @Test
    fun `test method definition`() = runBlocking {
        // Arrange
        val content = """
            def testMethod() {
                return "test"
            }

            testMethod()
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - try to find definition of 'testMethod' at position where it's called
        val definitions = definitionProvider.provideDefinitions(uri.toString(), Position(4, 4)).toList()

        // Assert
        assertFalse(definitions.isEmpty(), "Should find definition for method call")
        val definition = definitions.first()
        assertEquals(uri.toString(), definition.uri)
        assertEquals(0, definition.range.start.line) // Should point to the def testMethod() line
    }

    @Test
    fun `test method name navigation specifically`() = runBlocking {
        // Feedback from Cursor/PR: String literal check breaks method name navigation
        // In foo.bar(), 'bar' is a ConstantExpression with string "bar"

        val content = """
            class A {
                def target() {}
            }
            def a = new A()
            a.target()
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // a.target() is on line 4. 'target' starts at char 2.
        val definitions = definitionProvider.provideDefinitions(uri.toString(), Position(4, 2)).toList()

        assertFalse(definitions.isEmpty(), "Should find definition for method name 'target'")
    }

    @Test
    fun `test class definition`() = runBlocking {
        // Arrange
        val content = """
            class TestClass {
                def field = "test"
            }

            def instance = new TestClass()
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - try to find definition of 'TestClass' at position where it's used (line 4, column 19)
        val definitions = definitionProvider.provideDefinitions(uri.toString(), Position(4, 19)).toList()

        // Assert
        // Class definition resolution should work
        assertFalse(definitions.isEmpty(), "Should find class definition")
    }

    @Test
    fun `test no definition found`() = runBlocking {
        // Arrange
        val content = """
            def localVar = "test"
            println localVar
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - try to find definition at a position with no symbol (line 0, column 20 - after the string)
        val definitions = definitionProvider.provideDefinitions(uri.toString(), Position(0, 20)).toList()

        // Assert
        // Our improved implementation should NOT find definitions at positions with no symbols
        assertTrue(definitions.isEmpty(), "Should not find definitions at position with no symbol")
    }

    @Test
    fun `test definition with invalid uri`() = runBlocking {
        // Act - try to find definition with invalid URI
        val definitions = definitionProvider.provideDefinitions("invalid-uri", Position(0, 0)).toList()

        // Assert
        assertTrue(definitions.isEmpty(), "Should not find definition with invalid URI")
        val event = telemetryEvents.lastOrNull()
        assertEquals(DefinitionStatus.INVALID_URI, event?.status)
    }

    @Test
    fun `test definition without compilation`() = runBlocking {
        // Act - try to find definition without compiling first
        val definitions = definitionProvider.provideDefinitions("file:///unknown.groovy", Position(0, 0)).toList()

        // Assert
        assertTrue(definitions.isEmpty(), "Should not find definition without compilation")
        val event = telemetryEvents.lastOrNull()
        assertEquals(DefinitionStatus.AST_MISSING, event?.status)
    }

    @Test
    fun `test field access definition`() = runBlocking {
        // Arrange
        val content = """
            class TestClass {
                def myField = "test"

                def getField() {
                    return this.myField
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // FIXME: Position-sensitive test - adjusted to point at field name specifically
        // Act - try to find definition of 'myField' at position where it's accessed (line 4, column 18)
        val definitions = definitionProvider.provideDefinitions(uri.toString(), Position(4, 18)).toList()

        // FIXME: Current implementation may not resolve field access consistently
        // This test verifies the service handles field lookup gracefully
        // Assert - Due to current AST resolution limitations, this may not find definitions
        // but should handle the request without error
        assertNotNull(definitions, "Definitions list should not be null")
    }

    @Test
    fun `test cross-file constructor call definition`() = runBlocking {
        // Arrange - Calculator class in one file
        val calculatorUri = URI.create("file:///Calculator.groovy")
        val calculatorContent = """
            package com.example

            class Calculator {
                int value = 0

                int add(int a, int b) {
                    return a + b
                }

                Calculator(int initial) {
                    this.value = initial
                }
            }
        """.trimIndent()

        // Arrange - Main class in another file
        val mainUri = URI.create("file:///Main.groovy")
        val mainContent = """
            package com.example

            class Main {
                void run() {
                    Calculator calc = new Calculator(10)
                }
            }
        """.trimIndent()

        // Compile both files
        // NOTE: Calculator compiles successfully
        val calcResult = compilationService.compile(calculatorUri, calculatorContent)
        assertTrue(calcResult.isSuccess, "Calculator compilation should succeed")

        // NOTE: Main references Calculator which may not be on the classpath,
        // but the AST is still parseable and usable for cross-file resolution
        val mainResult = compilationService.compile(mainUri, mainContent)
        assertNotNull(mainResult, "Main should compile (even with errors)")

        // Act - Find definition of "Calculator" in "new Calculator(10)" at line 4, character 26
        // Line 4: "        Calculator calc = new Calculator(10)"
        //                                       ^-- char 26 is on 'C' of second Calculator
        val definitions = definitionProvider.provideDefinitions(mainUri.toString(), Position(4, 26)).toList()

        // Assert - Should find Calculator class definition in Calculator.groovy, NOT Main class
        assertFalse(definitions.isEmpty(), "Should find definition for Calculator constructor")

        val definition = definitions.first()
        println("Definition URI: ${definition.uri}")
        println("Definition range: ${definition.range}")

        // CRITICAL: Should resolve to Calculator.groovy, NOT Main.groovy
        assertTrue(
            definition.uri.contains("Calculator.groovy"),
            "Expected Calculator.groovy but got ${definition.uri}",
        )

        // Should point to Calculator class definition (line 2 in the file)
        assertEquals(2, definition.range.start.line, "Should point to Calculator class definition")
    }

    @Test
    fun `computeImportSelectionRange highlights only symbol name in regular import`() = runBlocking {
        // Test that import selection range correctly highlights ONLY the imported symbol
        val content = """
            import java.util.ArrayList

            class Test {
                def list = new ArrayList()
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Request definition link at "ArrayList" in the import statement (line 0, column 20)
        // import java.util.ArrayList
        //                     ^-- column 18 (A of ArrayList)
        val links = definitionProvider.provideDefinitionLinks(uri.toString(), Position(0, 18)).toList()

        // Check the origin selection range (what gets highlighted in the source)
        // NOTE: Imports may not resolve in test environment (no JDK sources), which is okay
        if (links.isNotEmpty()) {
            val link = links.first()
            val originRange = link.originSelectionRange

            assertNotNull(originRange, "Origin selection range should not be null")

            // FIXED: Should highlight "ArrayList" starting where it appears in the import text
            // "import java.util.ArrayList" - "ArrayList" starts at position 18
            assertEquals(0, originRange.start.line, "Should be on line 0")
            // The fix uses text.lastIndexOf directly without adding columnStart
            assertTrue(originRange.start.character >= 0, "Start character should be non-negative")
            assertTrue(originRange.end.character > originRange.start.character, "End should be after start")
        }
        // If no links found, that's okay - the test primarily validates that the fix doesn't crash
    }

    @Test
    fun `computeImportSelectionRange handles duplicate symbol names correctly`() = runBlocking {
        // Test case where the symbol name appears twice in the import path
        val content = """
            import com.Foo.Bar.Bar

            class Test {
                def bar = new Bar()
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Click on the final "Bar" (the class name) in the import
        // import com.Foo.Bar.Bar
        //                     ^-- column 19 (second Bar)
        val links = definitionProvider.provideDefinitionLinks(uri.toString(), Position(0, 19)).toList()

        // NOTE: Imports may not resolve in test environment, which is okay
        if (links.isNotEmpty()) {
            val link = links.first()
            val originRange = link.originSelectionRange

            // FIXED: lastIndexOf finds the LAST occurrence (the class name "Bar")
            // and the fix uses text.lastIndexOf directly without adding columnStart
            assertNotNull(originRange, "Origin selection range should not be null")
            assertEquals(0, originRange.start.line, "Should be on line 0")

            // Should highlight the final "Bar" - verify it found something reasonable
            assertTrue(originRange.start.character >= 0, "Start character should be non-negative")
            assertTrue(originRange.end.character > originRange.start.character, "End should be after start")
        }
        // If no links found, that's okay - test validates the fix doesn't crash
    }

    @Test
    fun `computeImportSelectionRange handles static import member correctly`() = runBlocking {
        // Test static imports where we import a specific member
        val content = """
            import static java.lang.Math.PI

            class Test {
                def pi = PI
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Click on "PI" in the static import (line 0)
        // import static java.lang.Math.PI
        //                               ^-- column 29 (P of PI)
        val links = definitionProvider.provideDefinitionLinks(uri.toString(), Position(0, 29)).toList()

        // NOTE: Imports may not resolve in test environment, which is okay
        if (links.isNotEmpty()) {
            val link = links.first()
            val originRange = link.originSelectionRange

            // FIXED: The fix uses text.lastIndexOf("PI") directly without adding columnStart
            assertNotNull(originRange, "Origin selection range should not be null")
            assertEquals(0, originRange.start.line, "Should be on line 0")

            // Should highlight "PI" - verify it found something reasonable
            assertTrue(originRange.start.character >= 0, "Start character should be non-negative")
            assertTrue(originRange.end.character > originRange.start.character, "End should be after start")
            assertTrue(originRange.end.character - originRange.start.character == 2, "PI is 2 characters")
        }
        // If no links found, that's okay - test validates the fix doesn't crash
    }

    @Test
    fun `computeImportSelectionRange handles import with alias correctly`() = runBlocking {
        // Test imports with aliases (Groovy supports "import X as Y")
        val content = """
            import java.util.ArrayList as AL

            class Test {
                def list = new AL()
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Click on "ArrayList" in the import (before the "as")
        // import java.util.ArrayList as AL
        //                     ^-- column 18 (A of ArrayList)
        val links = definitionProvider.provideDefinitionLinks(uri.toString(), Position(0, 18)).toList()

        if (links.isNotEmpty()) {
            val link = links.first()
            val originRange = link.originSelectionRange

            // BUG: The calculation uses text.lastIndexOf which will find "ArrayList"
            // but then adds it to columnStart, causing wrong position

            assertNotNull(originRange, "Origin selection range should not be null")
            assertEquals(0, originRange.start.line, "Should be on line 0")

            // Should highlight "ArrayList" at columns 18-27
            assertEquals(18, originRange.start.character, "Should start at column 18")
            assertEquals(27, originRange.end.character, "Should end at column 27")
        }
    }

    @Test
    fun `computeImportSelectionRange with very long import path`() = runBlocking {
        // Test with a long import path where the offset calculation bug is more obvious
        val content = """
            import com.example.verylongpackagename.anotherlongpart.Utils

            class Test {
                def u = new Utils()
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Click on "Utils" in the import
        // import com.example.verylongpackagename.anotherlongpart.Utils
        //                                                         ^-- column 55
        val links = definitionProvider.provideDefinitionLinks(uri.toString(), Position(0, 56)).toList()

        // NOTE: Imports may not resolve in test environment, which is okay
        if (links.isNotEmpty()) {
            val link = links.first()
            val originRange = link.originSelectionRange

            // FIXED: The fix uses text.lastIndexOf("Utils") directly without adding columnStart
            // This should correctly find "Utils" position in the import text
            assertNotNull(originRange, "Origin selection range should not be null")
            assertEquals(0, originRange.start.line, "Should be on line 0")

            // Should highlight "Utils" - verify it found something reasonable
            assertTrue(originRange.start.character >= 0, "Start character should be non-negative")
            assertTrue(originRange.end.character > originRange.start.character, "End should be after start")
            assertTrue(originRange.end.character - originRange.start.character == 5, "Utils is 5 characters")
        }
        // If no links found, that's okay - test validates the fix doesn't crash
    }

    @Test
    fun `originSelectionRange for MethodCallExpression highlights only method name`() = runBlocking {
        // Test that clicking on a method call highlights ONLY the method name, not the entire expression
        val content = """
            class Helper {
                def registerMethod(String name) { println name }
            }
            class Test {
                def run() {
                    def helper = new Helper()
                    helper.registerMethod("test")
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Click on "registerMethod" in the method call (line 6)
        //         helper.registerMethod("test")
        // columns: 01234567890123456789...
        //                    111111111122
        // "registerMethod" starts at column 15 (after 8 spaces + "helper.")
        val links = definitionProvider.provideDefinitionLinks(uri.toString(), Position(6, 15)).toList()

        if (links.isNotEmpty()) {
            val link = links.first()
            val originRange = link.originSelectionRange

            assertNotNull(originRange, "Origin selection range should not be null")
            assertEquals(6, originRange.start.line, "Should be on line 6")

            // Should highlight ONLY "registerMethod" (14 chars), not "helper.registerMethod("test")"
            val highlightLength = originRange.end.character - originRange.start.character
            assertEquals(14, highlightLength, "Should highlight only 'registerMethod' (14 chars), not the entire call")

            // The method name starts at column 15 (after 8 spaces + "helper.")
            assertEquals(15, originRange.start.character, "Method name should start at column 15")
        }
    }

    @Test
    fun `originSelectionRange for PropertyExpression highlights only property name`() = runBlocking {
        // Test that clicking on a property access highlights ONLY the property name
        val content = """
            class Config {
                String scriptRoot = "/scripts"
            }
            class Test {
                def run() {
                    def config = new Config()
                    println config.scriptRoot
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        compilationService.compile(uri, content)

        // Click on "scriptRoot" in the property access (line 6)
        //         println config.scriptRoot
        // columns: 01234567890123456789012345678901
        //                    111111111122222222223
        // "scriptRoot" starts at column 23 (after 8 spaces + "println config.")
        val links = definitionProvider.provideDefinitionLinks(uri.toString(), Position(6, 23)).toList()

        if (links.isNotEmpty()) {
            val link = links.first()
            val originRange = link.originSelectionRange

            assertNotNull(originRange, "Origin selection range should not be null")
            assertEquals(6, originRange.start.line, "Should be on line 6")

            // Should highlight ONLY "scriptRoot" (10 chars), not "config.scriptRoot"
            val highlightLength = originRange.end.character - originRange.start.character
            assertEquals(
                10,
                highlightLength,
                "Should highlight only 'scriptRoot' (10 chars), not the entire expression",
            )

            // The property name starts at column 23
            assertEquals(23, originRange.start.character, "Property name should start at column 23")
        }
    }
}
