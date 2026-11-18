// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("DEPRECATION") // the flat fallback answers with SymbolInformation
class DocumentOutlineTest {
    private val uri = URI.create("file:///Outline.groovy")
    private val documents = DocumentProvider()
    private val compilation = GroovyCompilationService(documentProvider = documents)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val service = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents),
    )

    @AfterEach
    fun close() {
        scope.cancel()
        compilation.shutdown()
    }

    private fun request(text: String): List<Either<SymbolInformation, DocumentSymbol>> {
        compilation.onDocumentOpened(uri)
        documents.put(uri, text, 1)
        return service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri.toString())))
            .get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun outline(text: String): List<DocumentSymbol> {
        service.updateHierarchicalDocumentSymbolSupport(true)
        return request(text).map { it.right }
    }

    private fun DocumentSymbol.all(): List<DocumentSymbol> = listOf(this) + children.orEmpty().flatMap { it.all() }

    private fun List<DocumentSymbol>.child(name: String) = single { it.name == name }

    @Test
    fun `members nest under their type in source order`() {
        val symbols = outline(
            """
            class Circle {
                final double radius
                Circle(double radius) { this.radius = radius }
                double area() { Math.PI * radius * radius }
            }
            """.trimIndent(),
        )

        val circle = symbols.single()
        assertEquals("Circle", circle.name)
        assertEquals(listOf("radius", "Circle", "area"), circle.children.map { it.name })
        assertEquals(
            listOf(SymbolKind.Property, SymbolKind.Constructor, SymbolKind.Method),
            circle.children.map { it.kind },
        )
    }

    @Test
    fun `types get their own kinds and enum constants are members`() {
        val symbols = outline(
            """
            interface Shape { double area() }
            trait Describable { String describe() { "d" } }
            enum Color {
                RED('#ff0000'), GREEN('#00ff00')
                final String hex
                Color(String hex) { this.hex = hex }
            }
            """.trimIndent(),
        )

        assertEquals(SymbolKind.Interface, symbols.child("Shape").kind)
        assertEquals(SymbolKind.Interface, symbols.child("Describable").kind)
        val color = symbols.child("Color")
        assertEquals(SymbolKind.Enum, color.kind)
        assertEquals(
            listOf("RED", "GREEN"),
            color.children.filter { it.kind == SymbolKind.EnumMember }.map { it.name },
        )
        val constructor = color.children.single { it.kind == SymbolKind.Constructor }
        assertEquals("Color", constructor.name)
        assertTrue("__" !in constructor.detail.orEmpty(), "generated enum parameters leak: ${constructor.detail}")
        assertTrue(symbols.none { it.name.contains("Trait") }, "generated trait helper is listed: $symbols")
    }

    @Test
    fun `nested types use source names and anonymous classes are left out`() {
        val symbols = outline(
            """
            class Outer {
                static class Inner { String greet(String who) { "hi" } }
                Runnable task() { new Runnable() { void run() {} } }
            }
            """.trimIndent(),
        )

        val outer = symbols.single()
        assertEquals(listOf("Inner", "task"), outer.children.map { it.name })
        assertEquals(SymbolKind.Class, outer.children.child("Inner").kind)
        assertEquals(listOf("greet"), outer.children.child("Inner").children.map { it.name })
        assertTrue(symbols.flatMap { it.all() }.none { '$' in it.name || '$' in it.detail.orEmpty() })
    }

    @Test
    fun `parameters, imports and the field behind a property are not symbols`() {
        val symbols = outline(
            """
            import java.util.concurrent.TimeUnit

            class Widget {
                String name
                int compute(int value) { value }
            }
            """.trimIndent(),
        )

        val all = symbols.flatMap { it.all() }
        assertEquals(listOf("Widget", "name", "compute"), all.map { it.name })
        assertEquals(listOf(SymbolKind.Class, SymbolKind.Property, SymbolKind.Method), all.map { it.kind })
    }

    @Test
    fun `selection range is the declared name inside the range`() {
        val symbols = outline("class Greeter {\n    void hello() {}\n}")

        val greeter = symbols.single()
        assertEquals(Range(Position(0, 6), Position(0, 13)), greeter.selectionRange)
        val hello = greeter.children.single()
        assertEquals(Range(Position(1, 9), Position(1, 14)), hello.selectionRange)
        assertTrue(hello.range.start.line <= 1 && hello.range.end.line >= 1)
        assertTrue(hello.range.start.character <= hello.selectionRange.start.character)
    }

    @Test
    fun `ranges are UTF-16 after a character outside the basic plane`() {
        val line = "    String s = \"😀\"; void hello() {}"
        val hello = outline("class A {\n$line\n}").single().children.child("hello")

        assertEquals(line.indexOf("void"), hello.range.start.character)
        assertEquals(line.indexOf("hello"), hello.selectionRange.start.character)
    }

    @Test
    fun `array and varargs types are written as in source`() {
        val symbols = outline(
            """
            class Main {
                static void main(String[] args) {}
                void add(String... items) {}
            }
            """.trimIndent(),
        )

        val details = symbols.single().children.map { it.detail }
        assertTrue(details.any { it.contains("String[] args") }, "array parameter: $details")
        assertTrue(details.none { ';' in it }, "JVM descriptor leaks: $details")
    }

    @Test
    fun `script methods are top level and script plumbing is hidden`() {
        val symbols = outline("def x = 1\ndef greet(String who) { who }\ngreet('a')\n")

        val names = symbols.flatMap { it.all() }.map { it.name }
        assertTrue("greet" in names, "script method missing: $names")
        assertTrue(names.none { it == "run" || it == "main" || it == "who" }, "script plumbing is listed: $names")
    }

    @Test
    fun `a client without hierarchical support gets a flat list naming each parent`() {
        val flat = request("class Circle {\n    double area() { 1 }\n}")

        assertTrue(flat.all { it.isLeft })
        val area = flat.map { it.left }.single { it.name == "area" }
        assertEquals("Circle", area.containerName)
        assertEquals(SymbolKind.Method, area.kind)
    }

    private companion object {
        const val WAIT_SECONDS = 20L
    }
}
