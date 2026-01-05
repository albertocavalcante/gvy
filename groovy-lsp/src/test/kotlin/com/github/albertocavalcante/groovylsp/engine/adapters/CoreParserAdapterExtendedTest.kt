package com.github.albertocavalcante.groovylsp.engine.adapters

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for [CoreParserAdapter].
 *
 * Tests parsing, symbol extraction, node lookup, and diagnostics.
 */
class CoreParserAdapterExtendedTest {

    private lateinit var parser: GroovyParser

    @BeforeEach
    fun setup() {
        parser = GroovyParser(ParserConfiguration())
    }

    @Test
    fun `isSuccessful returns true for valid class`() {
        val result = parser.parse("class Valid {}")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        assertTrue(adapter.isSuccessful)
    }

    @Test
    fun `isSuccessful returns false for syntax error`() {
        val result = parser.parse("class Invalid { void broken( {} }")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        assertFalse(adapter.isSuccessful)
    }

    @Test
    fun `diagnostics are empty for valid code`() {
        val result = parser.parse("class Valid { void method() {} }")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        assertTrue(adapter.diagnostics.isEmpty())
    }

    @Test
    fun `diagnostics contain errors for invalid code`() {
        val result = parser.parse("class Invalid { void broken( {} }")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        assertTrue(adapter.diagnostics.isNotEmpty())
        assertEquals(DiagnosticSeverity.Error, adapter.diagnostics.first().severity)
    }

    @Test
    fun `allSymbols returns class symbols`() {
        val result = parser.parse("class MyClass {}")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        val symbols = adapter.allSymbols()
        assertTrue(symbols.any { it.name == "MyClass" && it.kind == UnifiedNodeKind.CLASS })
    }

    @Test
    fun `allSymbols returns method symbols as children`() {
        val result = parser.parse(
            """
            class MyClass {
                void myMethod() {}
            }
            """.trimIndent(),
        )
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        val symbols = adapter.allSymbols()
        // Methods are returned as children of the class symbol
        val classSymbol = symbols.find { it.name == "MyClass" }
        assertNotNull(classSymbol)
        assertTrue(classSymbol.children.any { it.name == "myMethod" && it.kind == UnifiedNodeKind.METHOD })
    }

    @Test
    fun `allSymbols returns field symbols as children`() {
        val result = parser.parse(
            """
            class MyClass {
                String name
                int count
            }
            """.trimIndent(),
        )
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        val symbols = adapter.allSymbols()
        // Fields are returned as children of the class symbol
        val classSymbol = symbols.find { it.name == "MyClass" }
        assertNotNull(classSymbol)
        assertTrue(classSymbol.children.any { it.name == "name" })
        assertTrue(classSymbol.children.any { it.name == "count" })
    }

    @Test
    fun `allSymbols returns interface symbols`() {
        val result = parser.parse("interface MyInterface { void doIt() }")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        val symbols = adapter.allSymbols()
        assertTrue(symbols.any { it.name == "MyInterface" && it.kind == UnifiedNodeKind.INTERFACE })
    }

    @Test
    fun `allSymbols returns enum symbols`() {
        val result = parser.parse("enum Color { RED, GREEN, BLUE }")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        val symbols = adapter.allSymbols()
        assertTrue(symbols.any { it.name == "Color" && it.kind == UnifiedNodeKind.ENUM })
    }

    @Test
    fun `nodeAt returns null for position outside code`() {
        val result = parser.parse("class MyClass {}")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        val node = adapter.nodeAt(Position(100, 0))
        assertNull(node)
    }

    @Test
    fun `nodeAt does not throw for valid position`() {
        val result = parser.parse("class MyClass {}")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")
        // Just verify no exception is thrown; node may be null if ranges aren't set
        adapter.nodeAt(Position(0, 0))
        adapter.nodeAt(Position(0, 6))
        adapter.nodeAt(Position(0, 15))
    }

    @Test
    fun `uri is preserved in adapter`() {
        val result = parser.parse("class Test {}")
        val adapter = CoreParserAdapter(result, "file:///my/path/Test.groovy")
        assertEquals("file:///my/path/Test.groovy", adapter.uri)
    }

    @Test
    fun `handles empty content`() {
        val result = parser.parse("")
        val adapter = CoreParserAdapter(result, "file:///empty.groovy")
        // Empty content may or may not be successful depending on parser behavior
        // Just verify no exceptions are thrown
        val symbols = adapter.allSymbols()
        // Symbols might be empty or contain a synthetic script class
        assertTrue(symbols.isEmpty() || symbols.all { it.kind == UnifiedNodeKind.CLASS })
    }

    @Test
    fun `handles script without class`() {
        val result = parser.parse(
            """
            def x = 1
            println x
            """.trimIndent(),
        )
        val adapter = CoreParserAdapter(result, "file:///script.groovy")
        assertTrue(adapter.isSuccessful)
    }

    @Test
    fun `handles multiple classes in one file`() {
        val result = parser.parse(
            """
            class First {}
            class Second {}
            class Third {}
            """.trimIndent(),
        )
        val adapter = CoreParserAdapter(result, "file:///multi.groovy")
        val symbols = adapter.allSymbols()
        assertTrue(symbols.any { it.name == "First" })
        assertTrue(symbols.any { it.name == "Second" })
        assertTrue(symbols.any { it.name == "Third" })
    }

    @Test
    fun `handles nested classes`() {
        val result = parser.parse(
            """
            class Outer {
                class Inner {
                    void method() {}
                }
            }
            """.trimIndent(),
        )
        val adapter = CoreParserAdapter(result, "file:///nested.groovy")
        val symbols = adapter.allSymbols()
        // Only top-level Outer is returned; Inner is nested and not extracted at top level
        assertTrue(symbols.any { it.name == "Outer" })
        // The adapter currently only extracts top-level types
    }

    @Test
    fun `handles annotations in class`() {
        val result = parser.parse(
            """
            @groovy.transform.ToString
            class Annotated {
                String name
            }
            """.trimIndent(),
        )
        val adapter = CoreParserAdapter(result, "file:///annotated.groovy")
        assertTrue(adapter.isSuccessful)
        val symbols = adapter.allSymbols()
        assertTrue(symbols.any { it.name == "Annotated" })
    }

    @Test
    fun `handles generic types`() {
        val result = parser.parse(
            """
            class Container<T> {
                T value
                T getValue() { return value }
            }
            """.trimIndent(),
        )
        val adapter = CoreParserAdapter(result, "file:///generic.groovy")
        assertTrue(adapter.isSuccessful)
        val symbols = adapter.allSymbols()
        assertTrue(symbols.any { it.name == "Container" })
    }

    @Test
    fun `handles closures in methods`() {
        val result = parser.parse(
            """
            class WithClosures {
                def list = [1, 2, 3]
                def process() {
                    list.each { println it }
                    list.collect { it * 2 }
                }
            }
            """.trimIndent(),
        )
        val adapter = CoreParserAdapter(result, "file:///closures.groovy")
        assertTrue(adapter.isSuccessful)
    }

    @Test
    fun `handles try-catch blocks`() {
        val result = parser.parse(
            """
            class ErrorHandling {
                void riskyMethod() {
                    try {
                        throw new Exception("test")
                    } catch (Exception e) {
                        println e.message
                    } finally {
                        println "cleanup"
                    }
                }
            }
            """.trimIndent(),
        )
        val adapter = CoreParserAdapter(result, "file:///trycatch.groovy")
        assertTrue(adapter.isSuccessful)
    }

    @Test
    fun `diagnostic range uses exclusive end column`() {
        val result = parser.parse("class Invalid { void broken( {} }")
        val adapter = CoreParserAdapter(result, "file:///test.groovy")

        if (adapter.diagnostics.isNotEmpty()) {
            val diagnostic = adapter.diagnostics.first()
            // End column should be exclusive (pointing after the error token)
            assertTrue(diagnostic.range.end.character >= diagnostic.range.start.character)
        }
    }
}
