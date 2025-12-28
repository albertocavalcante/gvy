package com.github.albertocavalcante.groovylsp.providers

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
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
        // Arrays are now displayed as Type[] (we can't reliably detect varargs from AST)
        assertTrue(
            paramLabels.last().contains("Object[] args"),
            "Should show array param as Object[]. Found: $paramLabels",
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
        val (outerLineIndex, outerLine) = lineContaining(source, "printResult(sum")
        val outerPos = Position(outerLineIndex, outerLine.indexOf("printResult") + "printResult".length)

        val outerResult = signatureHelpProvider.provideSignatureHelp(uri.toString(), outerPos)
        assertEquals("void printResult(int val)", outerResult.signatures.firstOrNull()?.label ?: "null")
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
        val (lineIndex, line) = lineContaining(source, "method()")
        val position = Position(lineIndex, line.indexOf("method") + "method".length)

        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        assertTrue(result.signatures.isNotEmpty(), "Signatures should not be empty")
        val signature = result.signatures.first()
        val paramLabels = signature.parameters.mapNotNull { it.label?.left }

        // Verify default values are present
        assertTrue(
            paramLabels[0].contains(" = \"World\""),
            "Label should contain default value 'World': ${paramLabels[0]}",
        )
        assertTrue(paramLabels[1].contains(" = 3"), "Label should contain default value '3': ${paramLabels[1]}")
    }

    // --- Edge Case Tests ---

    @Test
    fun `displays array parameters correctly as Type brackets`() = runTest {
        val uri = URI.create("file:///ArrayParam.groovy")
        val source = """
            class ArrayProcessor {
                def process(String[] items, int[] counts) {}
                def run() {
                    process(null, null)
                }
            }
        """.trimIndent()

        compile(uri, source)
        // Currently affected by AST resolution - documenting expected behavior
        val (lineIndex, line) = lineContaining(source, "process(null")
        val position = Position(lineIndex, line.indexOf("process") + "process".length)
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        val signature = result.signatures.first()
        val paramLabels = signature.parameters.mapNotNull { it.label?.left }
        assertEquals("String[] items", paramLabels[0], "First param should be String[]")
        assertEquals("int[] counts", paramLabels[1], "Second param should be int[]")
    }

    @Test
    fun `handles multiple method overloads`() = runTest {
        val uri = URI.create("file:///Overloads.groovy")
        val source = """
            class Calculator {
                int add(int a, int b) { a + b }
                int add(int a, int b, int c) { a + b + c }
                double add(double a, double b) { a + b }
                def run() {
                    add(1, 2)
                }
            }
        """.trimIndent()

        compile(uri, source)
        val position = positionAfter(source, "add(1, ")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        // Should find all 3 overloads
        assertTrue(result.signatures.size >= 3, "Should find at least 3 overloads. Found: ${result.signatures.size}")
        val labels = result.signatures.map { it.label }
        assertTrue(labels.any { it.contains("int a, int b)") && !it.contains("int c") }, "Missing 2-param int overload")
        assertTrue(labels.any { it.contains("int a, int b, int c") }, "Missing 3-param int overload")
        assertTrue(labels.any { it.contains("double a, double b") }, "Missing double overload")
    }

    @Test
    fun `handles explicit Object receiver for hashCode`() = runTest {
        val uri = URI.create("file:///ExplicitObject.groovy")
        val source = """
            class ObjectTest {
                def run() {
                    Object obj = "hello"
                    obj.hashCode()
                }
            }
        """.trimIndent()

        compile(uri, source)
        // Currently fails due to AST node resolution at parenthesis boundary
        // When supported:
        val position = positionAfter(source, "obj.hashCode(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)
        assertTrue(result.signatures.isNotEmpty(), "Should find Object.hashCode() signature")
    }

    // --- Future Improvement Tests (Disabled with TODOs) ---

    @Test
    fun `TODO - constructor signature help`() = runTest {
        // TODO(FUTURE): Constructor signature help not yet implemented
        // When implemented, this test should verify signature help for `new Foo(...)` calls
        val uri = URI.create("file:///ConstructorHelp.groovy")
        val source = """
            class Person {
                String name
                int age
                Person(String name, int age) {
                    this.name = name
                    this.age = age
                }
            }
            class Consumer {
                def run() {
                    new Person("Alice", 30)
                }
            }
        """.trimIndent()

        compile(uri, source)
        // Constructor calls are not yet supported - this test documents the limitation
        // When supported, uncomment the following:
        /*
        val position = positionAfter(source, "new Person(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)
        assertTrue(result.signatures.isNotEmpty(), "Should find constructor signature")
        assertEquals("Person(String name, int age)", result.signatures.first().label)
         */
    }

    @Test
    fun `TODO - chained method call signature help`() = runTest {
        // TODO(FUTURE): Chained method call resolution may need improvement
        // This test documents the expected behavior for chained calls
        val uri = URI.create("file:///ChainedCalls.groovy")
        val source = """
            class Builder {
                Builder setName(String name) { this }
                Builder setAge(int age) { this }
                def run() {
                    new Builder().setName("test").setAge(25)
                }
            }
        """.trimIndent()

        compile(uri, source)
        // Chained calls work if type inference resolves the receiver
        // This test documents expected behavior
        val position = positionAfter(source, ".setAge(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        // May or may not work depending on type inference - documenting current state
        // When fully supported, this should pass:
        // assertEquals("Builder setAge(int age)", result.signatures.first().label)
    }

    @Test
    fun `TODO - closure parameter display`() = runTest {
        // TODO(FUTURE): Closure parameters could show more details (param types, return type)
        // Also affected by AST resolution issues at certain cursor positions
        val uri = URI.create("file:///ClosureParam.groovy")
        val source = """
            class Processor {
                def process(Closure action) {}
                def run() {
                    process { it * 2 }
                }
            }
        """.trimIndent()

        compile(uri, source)
        // Currently affected by AST resolution - documenting expected behavior
        /*
        val position = positionAfter(source, "process(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)
        assertTrue(result.signatures.isNotEmpty(), "Should find process signature")
        val paramLabel = result.signatures.first().parameters.firstOrNull()?.label?.left
        assertTrue(paramLabel?.contains("Closure") == true, "Should show Closure param")
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
