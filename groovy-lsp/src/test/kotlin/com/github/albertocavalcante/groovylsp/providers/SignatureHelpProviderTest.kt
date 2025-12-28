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
        assertEquals("myMethod(String arg1, int arg2)", signature.label)
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

    @Test
    fun `returns signatures for Script-level println GDK method`() = runTest {
        // println() is a GDK method inherited from groovy.lang.Script
        val uri = URI.create("file:///ScriptPrintln.groovy")
        val source = """
            println("hello")
        """.trimIndent()

        compile(uri, source)

        // Position inside println parens: println("hello")
        //                                       ^ char 8
        val position = Position(0, 8)
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        // Should find at least one println signature from groovy.lang.Script
        assertTrue(
            result.signatures.isNotEmpty(),
            "Expected signatures for println() GDK method, but got empty. " +
                "Signatures: ${result.signatures.map { it.label }}",
        )
    }
}
