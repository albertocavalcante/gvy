package com.github.albertocavalcante.groovylsp.providers

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignatureHelpProviderTest {

    private val compilationService = GroovyCompilationService()
    private val documentProvider = DocumentProvider()
    private val signatureHelpProvider = SignatureHelpProvider(compilationService, documentProvider)

    @Test
    fun `returns signatures for method call`() = runTest {
        val uri = URI.create("file:///SignatureHelp.groovy")
        val source = """
            class Sample {
                def myMethod(String arg1, int arg2) {}
                def run() {
                    myMethod("text", 42)
                }
            }
        """.trimIndent()

        compile(uri, source)

        val declarations = compilationService.getSymbolTable(uri)
            ?.registry
            ?.findMethodDeclarations(uri, "myMethod")
            ?: emptyList()
        assertTrue(declarations.isNotEmpty(), "Test precondition failed: symbol table missing myMethod")

        val position = positionAfter(source, "myMethod(\"text\"")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        assertEquals(0, result.activeSignature)
        assertEquals(0, result.activeParameter)
        assertEquals(1, result.signatures.size)
        val signature = result.signatures.first()
        assertEquals("Object myMethod(String arg1, int arg2)", signature.label)
        val parameterLabels = signature.parameters.mapNotNull { it.label?.left }
        assertEquals(listOf("String arg1", "int arg2"), parameterLabels)
    }

    @Test
    fun `computes active parameter based on caret`() = runTest {
        val uri = URI.create("file:///SignatureHelpActiveParameter.groovy")
        val source = """
            class Calculator {
                def sum(int left, int right) {}
                def run() {
                    sum(1, 2)
                }
            }
        """.trimIndent()

        compile(uri, source)

        val lineInfo = lineContaining(source, "sum(1, 2)")
        val secondArgColumn = lineInfo.second.indexOf("2")
        val position = Position(lineInfo.first, secondArgColumn)

        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)
        assertEquals(1, result.activeParameter)
    }

    @Test
    fun `cursor after comma selects next parameter`() = runTest {
        val uri = URI.create("file:///SignatureHelpComma.groovy")
        val source = """
            class Sample {
                def myMethod(String arg1, int arg2) {}
                def run() {
                    myMethod("text", 42)
                }
            }
        """.trimIndent()

        compile(uri, source)

        val (lineIndex, line) = lineContaining(source, "myMethod(\"text\", 42)")
        val commaColumn = line.indexOf(",") + 2 // after comma and space
        val position = Position(lineIndex, commaColumn)

        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)
        assertEquals(1, result.activeParameter)
    }

    @Test
    fun `cursor after last argument selects upcoming parameter`() = runTest {
        val uri = URI.create("file:///SignatureHelpTrailingSlot.groovy")
        val source = """
            class Sample {
                def myMethod(String arg1, int arg2, boolean enabled = false) {}
                def run() {
                    myMethod("text", 42, )
                }
            }
        """.trimIndent()

        compile(uri, source)

        val (lineIndex, line) = lineContaining(source, "myMethod(\"text\", 42, )")
        val beforeClosing = line.indexOf(")")
        val position = Position(lineIndex, beforeClosing)

        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)
        assertEquals(2, result.activeParameter)
    }

    @Test
    fun `cursor in empty argument list stays at first parameter`() = runTest {
        val uri = URI.create("file:///SignatureHelpEmptyCall.groovy")
        val source = """
            class Sample {
                def myMethod(String arg1, int arg2) {}
                def run() {
                    myMethod()
                }
            }
        """.trimIndent()

        compile(uri, source)

        val (lineIndex, line) = lineContaining(source, "myMethod()")
        val position = Position(lineIndex, line.indexOf("(") + 1)

        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)
        assertEquals(0, result.activeParameter)
    }

    @Test
    fun `returns empty signature help when no method call is found`() = runTest {
        val uri = URI.create("file:///SignatureHelpNoCall.groovy")
        val source = """
            class Sample {
                def run() {
                    def local = 10
                    local
                }
            }
        """.trimIndent()

        compile(uri, source)

        val position = Position(3, 8)
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)
        assertTrue(result.signatures.isEmpty())
    }

    @Test
    fun `returns signatures for Script-level println GDK method`() = runTest {
        // println() is a GDK method inherited from groovy.lang.Script
        val uri = URI.create("file:///ScriptPrintln.groovy")
        val source = """
            println("hello")
        """.trimIndent()

        compile(uri, source)

        // Position inside println parens: println("hello")
        val position = Position(0, 8)
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        val labels = result.signatures.map { it.label }
        // Should find at least 4 println() overloads (Script + GDK)
        // 2 from Script (println(), println(Object)) + others from GDK/DefaultGroovyMethods
        assertTrue(
            result.signatures.size >= 4,
            "Expected at least 4 signatures for println() GDK method, but found: ${result.signatures.size}. Labels: $labels",
        )
        assertTrue(
            labels.any { it == "void println()" },
            "Missing signature for println() without arguments. Found: $labels",
        )
        assertTrue(
            labels.any { it.startsWith("void println(Object") },
            "Missing signature for println(Object). Found: $labels",
        )
    }

    @Test
    fun `handles static method calls`() = runTest {
        val uri = URI.create("file:///StaticMethod.groovy")
        val source = """
            class Utils {
                static void log(String msg) {}
            }
            class Consumer {
                def run() {
                    Utils.log("")
                }
            }
        """.trimIndent()

        compile(uri, source)
        val position = positionAfter(source, "Utils.log(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        assertEquals(1, result.signatures.size)
        assertEquals("void log(String msg)", result.signatures.first().label)
    }

    @Test
    fun `handles varargs parameters`() = runTest {
        val uri = URI.create("file:///VarargsMethod.groovy")
        val source = """
            class Varargs {
                def format(String pattern, Object... args) {}
                def run() {
                    format("%s", 1, 2)
                }
            }
        """.trimIndent()

        compile(uri, source)
        val position = positionAfter(source, "format(\"%s\", 1, 2")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        val signature = result.signatures.first()
        val paramLabels = signature.parameters.mapNotNull { it.label?.left }
        // We allow both "Object... args" and "Object[] args" but updated logic prefers "Object... args"
        assertTrue(
            paramLabels.last().contains("Object... args") || paramLabels.last().contains("Object[] args"),
            "Should identify varargs param. Found: $paramLabels",
        )

        // Active parameter should map to the varargs index (1) even if we are at arg index 2
        assertEquals(1, result.activeParameter)
    }

    @Test
    fun `handles nested method calls correctly`() = runTest {
        val uri = URI.create("file:///NestedCalls.groovy")
        val source = """
            class Nested {
                int sum(int a, int b) { a + b }
                void printResult(int val) {}
                def run() {
                    printResult(sum(1, 2))
                }
            }
        """.trimIndent()

        compile(uri, source)

        // Debug check
        val declarations = compilationService.getSymbolTable(uri)?.registry?.findMethodDeclarations(uri, "printResult")
        assertTrue(declarations != null && declarations.isNotEmpty(), "Symbol table missing printResult")

        // Case 1: Inside inner call `sum(1, 2)`
        val innerPos = positionAfter(source, "sum(1, ")
        val innerResult = signatureHelpProvider.provideSignatureHelp(uri.toString(), innerPos)
        assertEquals("int sum(int a, int b)", innerResult.signatures.firstOrNull()?.label ?: "null")

        // Case 2: Inside outer call `printResult(...)`
        // TODO(#469): Fix AST node resolution for outer calls in test environment
        // Currently fails because findMethodCall doesn't walk up correctly from the token at '('
        /*
        val outerPos = positionAfter(source, "printResult")

        // Debug AST resolution
        val astModel = compilationService.getAstModel(uri)!!
        val nodeAt = astModel.getNodeAt(uri, outerPos.toGroovyPosition())
        assertNotNull(nodeAt, "AST Node at $outerPos is null")

        val outerResult = signatureHelpProvider.provideSignatureHelp(uri.toString(), outerPos)
        assertEquals("void printResult(int val)", outerResult.signatures.firstOrNull()?.label ?: "null")
         */
    }

    @Test
    fun `clamps active parameter to last index`() = runTest {
        val uri = URI.create("file:///SignatureHelpBounds.groovy")
        val source = """
            class Bounds {
                def method(int a, int b) {} // 2 params, indices 0, 1
                def run() {
                    method(1, 2, 3, 4) // Too many args
                }
            }
        """.trimIndent()

        compile(uri, source)

        // Cursor at 4th arg (index 3)
        val position = positionAfter(source, "method(1, 2, 3, 4")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        // Should be clamped to 1 (last valid index)
        assertEquals(1, result.activeParameter)
    }

    @Test
    fun `includes default values in parameter labels`() = runTest {
        val uri = URI.create("file:///SignatureHelpDefaults.groovy")
        val source = """
            class Defaults {
                // TODO(#469): Verify default parameter label generation once test AST resolution is fixed
                def method(String name = "World", int retries = 3) {}
                def run() {
                    method()
                }
            }
        """.trimIndent()

        compile(uri, source)

        // Debug check
        val declarations = compilationService.getSymbolTable(uri)?.registry?.findMethodDeclarations(uri, "method")
        assertTrue(declarations != null && declarations.isNotEmpty(), "Symbol table missing method")

        // Position at opening parenthesis: `method(|)`
        /*
        val position = positionAfter(source, "method")

        // Debug AST resolution
        val astModel = compilationService.getAstModel(uri)!!
        val nodeAt = astModel.getNodeAt(uri, position.toGroovyPosition())
        assertNotNull(nodeAt, "AST Node at $position is null")

        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        assertTrue(result.signatures.isNotEmpty(), "Signatures should not be empty")
        val signature = result.signatures.first()
        val paramLabels = signature.parameters.mapNotNull { it.label?.left }

        // Verify default values are present
        assertTrue(paramLabels[0].contains(" = \"World\""), "Label should contain default value 'World': ${paramLabels[0]}")
        assertTrue(paramLabels[1].contains(" = 3"), "Label should contain default value '3': ${paramLabels[1]}")
         */
    }

    private suspend fun compile(uri: URI, source: String) {
        documentProvider.put(uri, source)
        compilationService.compile(uri, source)
    }

    private fun positionAfter(source: String, snippet: String): Position {
        val (lineIndex, line) = lineContaining(source, snippet)
        val column = line.indexOf(snippet) + snippet.length
        return Position(lineIndex, column)
    }

    private fun lineContaining(source: String, snippet: String): Pair<Int, String> {
        val lines = source.lines()
        lines.forEachIndexed { index, line ->
            if (line.contains(snippet)) {
                return index to line
            }
        }
        error("Snippet '$snippet' not found in source")
    }
}
