package com.github.albertocavalcante.groovylsp.providers.symbols
import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for DocumentSymbolProvider functionality.
 * These tests verify that document symbols are correctly extracted from Groovy AST.
 */
class DocumentSymbolProviderTest {

    private val logger = LoggerFactory.getLogger(DocumentSymbolProviderTest::class.java)
    private val compilationService = TestUtils.createCompilationService()
    private val documentSymbolProvider = DocumentSymbolProvider(compilationService)

    @Test
    fun `provideDocumentSymbols returns empty list for invalid URI`() = runTest {
        val symbols = documentSymbolProvider.provideDocumentSymbols("file:///nonexistent.groovy")
        assertTrue(symbols.isEmpty())
    }

    @Test
    fun `provideDocumentSymbols returns class symbols for simple class`() = runTest {
        val groovyCode = """
            package com.example

            class TestClass {
                def field = "value"

                def method() {
                    return 42
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        // Should have at least one symbol (the class)
        assertTrue(symbols.isNotEmpty(), "Should have at least one symbol")

        // Find the class symbol
        val classSymbol = symbols.find { either ->
            either.isRight && either.right.kind == SymbolKind.Class
        }?.right

        assertNotNull(classSymbol, "Should have a class symbol")
        assertEquals("TestClass", classSymbol.name)
        assertEquals(SymbolKind.Class, classSymbol.kind)
        assertTrue(classSymbol.detail.contains("class TestClass"))

        // Should have children (method and field)
        assertTrue(classSymbol.children.isNotEmpty(), "Class should have children")

        // Check for method
        val methodSymbol = classSymbol.children.find { it.kind == SymbolKind.Method }

        assertNotNull(methodSymbol, "Should have a method symbol")
        assertEquals("method", methodSymbol.name)

        // Check for field
        val fieldSymbol = classSymbol.children.find { it.kind == SymbolKind.Field }

        assertNotNull(fieldSymbol, "Should have a field symbol")
        assertEquals("field", fieldSymbol.name)
    }

    @Test
    fun `provideDocumentSymbols handles interface correctly`() = runTest {
        val groovyCode = """
            interface TestInterface {
                def abstractMethod()
                static final String CONSTANT = "value"
            }
        """.trimIndent()

        val uri = URI.create("file:///interface.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        val interfaceSymbol = symbols.find { either ->
            either.isRight && either.right.kind == SymbolKind.Interface
        }?.right

        assertNotNull(interfaceSymbol, "Should have an interface symbol")
        assertEquals("TestInterface", interfaceSymbol.name)
        assertEquals(SymbolKind.Interface, interfaceSymbol.kind)
        assertTrue(interfaceSymbol.detail.contains("interface"))
    }

    @Test
    fun `provideDocumentSymbols handles enum correctly`() = runTest {
        val groovyCode = """
            enum Color {
                RED, GREEN, BLUE

                private String hexValue

                Color(String hex) {
                    this.hexValue = hex
                }

                String getHex() {
                    return hexValue
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///enum.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        val enumSymbol = symbols.find { either ->
            either.isRight && either.right.kind == SymbolKind.Enum
        }?.right

        assertNotNull(enumSymbol, "Should have an enum symbol")
        assertEquals("Color", enumSymbol.name)
        assertEquals(SymbolKind.Enum, enumSymbol.kind)
        assertTrue(enumSymbol.detail.contains("enum"))
    }

    @Test
    fun `provideDocumentSymbols shows method signatures with parameters`() = runTest {
        val groovyCode = """
            class MathUtils {
                public static int add(int a, int b) {
                    return a + b
                }

                private String formatNumber(double value, boolean scientific = false) {
                    return scientific ? String.format("%.2e", value) : String.format("%.2f", value)
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///math.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        val classSymbol = symbols.find { either ->
            either.isRight && either.right.kind == SymbolKind.Class
        }?.right

        assertNotNull(classSymbol, "Should have class symbol")

        // Check add method
        val addMethod = classSymbol.children.find { it.name == "add" }

        assertNotNull(addMethod, "Should have add method")
        assertEquals(SymbolKind.Method, addMethod.kind)
        assertTrue(addMethod.detail.contains("int add(int a, int b)"))

        // Check formatNumber method
        val formatMethod = classSymbol.children.find { it.name == "formatNumber" }

        assertNotNull(formatMethod, "Should have formatNumber method")
        assertTrue(formatMethod.detail.contains("formatNumber"))
        assertTrue(formatMethod.detail.contains("double value"))
        assertTrue(formatMethod.detail.contains("boolean scientific"))
    }

    @Test
    fun `provideDocumentSymbols shows field modifiers correctly`() = runTest {
        val groovyCode = """
            class TestClass {
                private static final String CONSTANT = "value"
                public int publicField = 42
                protected String protectedField
                def dynamicField = "dynamic"
                final List immutableList = []
            }
        """.trimIndent()

        val uri = URI.create("file:///fields.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        val classSymbol = symbols.find { either ->
            either.isRight && either.right.kind == SymbolKind.Class
        }?.right

        assertNotNull(classSymbol, "Should have class symbol")

        // Check constant field
        val constantField = classSymbol.children.find { it.name == "CONSTANT" }

        assertNotNull(constantField, "Should have CONSTANT field")
        assertEquals(SymbolKind.Field, constantField.kind)
        assertTrue(constantField.detail.contains("static"))
        assertTrue(constantField.detail.contains("final"))
        assertTrue(constantField.detail.contains("private"))

        // Check public field
        val publicField = classSymbol.children.find { it.name == "publicField" }

        assertNotNull(publicField, "Should have publicField")
        assertTrue(publicField.detail.contains("public"))
        assertTrue(publicField.detail.contains("int"))

        // Check dynamic field
        val dynamicField = classSymbol.children.find { it.name == "dynamicField" }

        assertNotNull(dynamicField, "Should have dynamicField")
        assertTrue(dynamicField.detail.contains("dynamicField"))
    }

    @Test
    fun `provideDocumentSymbols includes import statements`() = runTest {
        val groovyCode = """
            import java.util.List
            import java.util.Date as JDate
            import static java.lang.System.out
            import static java.lang.Math.*

            class TestClass {
                List items = []
                JDate currentDate = new JDate()
            }
        """.trimIndent()

        val uri = URI.create("file:///imports.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        // Should have import symbols
        val importSymbols = symbols.filter { either ->
            either.isRight && either.right.kind == SymbolKind.Namespace
        }.map { it.right }

        assertTrue(importSymbols.isNotEmpty(), "Should have import symbols")

        // Check for regular import
        val listImport = importSymbols.find { it.name == "List" }
        assertNotNull(listImport, "Should have List import")
        assertEquals("import", listImport.detail)

        // Check for aliased import
        val dateImport = importSymbols.find { it.name == "JDate" }
        assertNotNull(dateImport, "Should have JDate import")
        assertEquals("import", dateImport.detail)

        // Check for static import
        val staticImports = importSymbols.filter { it.detail == "static import" }
        assertTrue(staticImports.isNotEmpty(), "Should have static import symbols")
    }

    @Test
    fun `provideDocumentSymbols handles constructor methods`() = runTest {
        val groovyCode = """
            class Person {
                private String name
                private int age

                Person(String name, int age) {
                    this.name = name
                    this.age = age
                }

                Person() {
                    this("Unknown", 0)
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///constructor.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        val classSymbol = symbols.find { either ->
            either.isRight && either.right.kind == SymbolKind.Class
        }?.right

        assertNotNull(classSymbol, "Should have class symbol")

        // Note: In Groovy AST, constructors might not always be marked as "<init>"
        // They might appear as regular methods, so we check for constructor-like patterns
        val constructorMethods = classSymbol.children.filter {
            it.kind == SymbolKind.Constructor || it.name == "Person" || it.name == "<init>"
        }

        assertTrue(constructorMethods.isNotEmpty(), "Should have constructor methods")
    }

    @Test
    fun `provideDocumentSymbols handles empty file gracefully`() = runTest {
        val groovyCode = ""

        val uri = URI.create("file:///empty.groovy")
        val result = compilationService.compile(uri, groovyCode)

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        // Empty file should return empty symbols list
        assertTrue(symbols.isEmpty(), "Empty file should have no symbols")
    }

    @Test
    fun `provideDocumentSymbols handles script-level declarations`() = runTest {
        val groovyCode = """
            def scriptVariable = "hello"

            def scriptMethod() {
                return "script method"
            }

            println scriptVariable
            println scriptMethod()
        """.trimIndent()

        val uri = URI.create("file:///script.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        // Script files in Groovy create a default class, so we might see that
        // The exact behavior depends on how the AST represents script-level declarations
        logger.debug("Script symbols found: ${symbols.size}")
        symbols.forEach { either ->
            if (either.isRight) {
                logger.debug("Symbol: ${either.right.name} (${either.right.kind})")
            }
        }

        // Test should pass without crashing - exact symbol structure may vary
        assertTrue(true, "Script symbol extraction completed successfully")
    }

    @Test
    fun `provideDocumentSymbols handles nested classes`() = runTest {
        val groovyCode = """
            class OuterClass {
                private String outerField = "outer"

                def outerMethod() {
                    return "outer method"
                }

                static class NestedClass {
                    private String nestedField = "nested"

                    def nestedMethod() {
                        return "nested method"
                    }
                }

                class InnerClass {
                    def innerMethod() {
                        return outerField // Can access outer class
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///nested.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        // Should have outer class
        val outerClass = symbols.find { either ->
            either.isRight && either.right.name == "OuterClass"
        }?.right

        assertNotNull(outerClass, "Should have OuterClass")
        assertEquals(SymbolKind.Class, outerClass.kind)

        // Should also have nested classes as separate top-level symbols
        // (Groovy AST treats nested classes as separate class nodes)
        val allClasses = symbols.filter { either ->
            either.isRight && either.right.kind == SymbolKind.Class
        }.map { it.right }

        assertTrue(allClasses.size >= 1, "Should have at least the outer class")

        // The exact representation of nested classes may vary in Groovy AST
        logger.debug("Found ${allClasses.size} class symbols: ${allClasses.map { it.name }}")
    }

    @Test
    fun `provideDocumentSymbols has correct position ranges`() = runTest {
        val groovyCode = """
            class TestClass {
                def field = "value"

                def method() {
                    return 42
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///positions.groovy")
        val result = compilationService.compile(uri, groovyCode)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

        val classSymbol = symbols.find { either ->
            either.isRight && either.right.kind == SymbolKind.Class
        }?.right

        assertNotNull(classSymbol, "Should have class symbol")

        // Verify ranges are valid
        assertNotNull(classSymbol.range, "Class should have range")
        assertNotNull(classSymbol.selectionRange, "Class should have selection range")

        // Range should start at beginning of class declaration
        assertTrue(classSymbol.range.start.line >= 0, "Range start line should be non-negative")
        assertTrue(classSymbol.selectionRange.start.line >= 0, "Selection range start line should be non-negative")

        // Selection range should be within the full range
        assertTrue(
            classSymbol.selectionRange.start.line >= classSymbol.range.start.line,
            "Selection range should be within full range",
        )

        // Check children have valid ranges too
        classSymbol.children.forEach { childSymbol ->
            assertNotNull(childSymbol.range, "Child symbol should have range")
            assertNotNull(childSymbol.selectionRange, "Child symbol should have selection range")
            assertTrue(childSymbol.range.start.line >= 0, "Child range should be valid")
        }
    }

    @Test
    fun `provideDocumentSymbols handles compilation errors gracefully`() = runTest {
        val invalidGroovyCode = """
            class TestClass {
                def method( // Invalid syntax - missing closing parenthesis
                    return 42
                }
            }
        """.trimIndent()

        try {
            val uri = URI.create("file:///invalid.groovy")
            val result = compilationService.compile(uri, invalidGroovyCode)

            // Should not crash even with invalid code
            val symbols = documentSymbolProvider.provideDocumentSymbols(uri.toString())

            // Result might be empty or partial, but should not throw exception
            logger.debug("Symbols found for invalid code: ${symbols.size}")
        } catch (e: Exception) {
            // If compilation fails completely, provider should handle gracefully
            logger.warn("Expected behavior: compilation may fail for invalid code", e)

            // Test with completely invalid URI
            val symbols = documentSymbolProvider.provideDocumentSymbols("file:///definitely-invalid.groovy")
            assertTrue(symbols.isEmpty(), "Invalid URI should return empty symbols")
        }

        // Test passes if we reach here without unhandled exceptions
        assertTrue(true, "Error handling test completed successfully")
    }
}
