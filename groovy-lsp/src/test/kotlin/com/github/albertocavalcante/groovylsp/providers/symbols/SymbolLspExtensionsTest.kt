package com.github.albertocavalcante.groovylsp.providers.symbols

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.net.URI

class SymbolLspExtensionsTest {

    @Test
    @Suppress("DEPRECATION")
    fun `class symbol converts to document and symbol information`() {
        val classNode = ClassNode("Greeter", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE).apply {
            setLineNumber(1)
            setColumnNumber(1)
            setLastLineNumber(1)
            setLastColumnNumber(10)
        }
        val uri = URI.create("file:///test.groovy")

        val symbol = Symbol.Class.from(classNode, uri)

        val documentSymbol = symbol.toDocumentSymbol()
        assertNotNull(documentSymbol)
        require(documentSymbol != null)
        assertEquals(SymbolKind.Class, documentSymbol.kind)
        assertEquals("Greeter", documentSymbol.name)
        assertEquals("Greeter", documentSymbol.detail)

        val symbolInformation = symbol.toSymbolInformation()
        assertNotNull(symbolInformation)
        require(symbolInformation != null)
        assertEquals(SymbolKind.Class, symbolInformation.kind)
        assertEquals(uri.toString(), symbolInformation.location.uri)
        assertEquals(0, symbolInformation.location.range.start.line)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `method symbol includes signature in detail`() {
        val classNode = ClassNode("Greeter", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val methodNode = MethodNode(
            "greet",
            Modifier.PUBLIC,
            ClassHelper.STRING_TYPE,
            arrayOf(Parameter(ClassHelper.STRING_TYPE, "name")),
            ClassNode.EMPTY_ARRAY,
            null,
        ).apply {
            declaringClass = classNode
            setLineNumber(2)
            setColumnNumber(1)
            setLastLineNumber(2)
            setLastColumnNumber(20)
        }
        val uri = URI.create("file:///test.groovy")

        val methodSymbol = Symbol.Method.from(methodNode, uri)

        val documentSymbol = methodSymbol.toDocumentSymbol()
        assertNotNull(documentSymbol)
        require(documentSymbol != null)
        assertEquals(SymbolKind.Method, documentSymbol.kind)
        assertEquals("greet", documentSymbol.name)
        assertEquals("public String greet(String name)", documentSymbol.detail)

        val symbolInformation = methodSymbol.toSymbolInformation()
        assertNotNull(symbolInformation)
        require(symbolInformation != null)
        assertEquals(SymbolKind.Method, symbolInformation.kind)
        assertEquals(uri.toString(), symbolInformation.location.uri)
        assertEquals("public String greet(String name)", methodSymbol.signature)
    }

    @Test
    fun `constructor symbol uses fallback name when owner missing`() {
        val constructorNode = ConstructorNode(
            Modifier.PUBLIC,
            arrayOf(Parameter(ClassHelper.STRING_TYPE, "name")),
            ClassNode.EMPTY_ARRAY,
            BlockStatement(),
        ).apply {
            setLineNumber(1)
            setColumnNumber(1)
            setLastLineNumber(1)
            setLastColumnNumber(10)
        }
        val uri = URI.create("file:///test.groovy")

        val constructorSymbol = Symbol.Method.from(constructorNode, uri)

        val documentSymbol = constructorSymbol.toDocumentSymbol()
        assertNotNull(documentSymbol)
        require(documentSymbol != null)
        assertEquals("constructor", documentSymbol.name)
        assertEquals(SymbolKind.Constructor, documentSymbol.kind)
        assertEquals("public constructor(String name)", documentSymbol.detail)
        assertEquals("public constructor(String name)", constructorSymbol.signature)
    }

    @Test
    fun `constructor symbol uses class name in signature`() {
        val classNode = ClassNode("Widget", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val constructorNode = ConstructorNode(
            Modifier.PUBLIC,
            arrayOf(Parameter(ClassHelper.STRING_TYPE, "name")),
            ClassNode.EMPTY_ARRAY,
            BlockStatement(),
        ).apply {
            declaringClass = classNode
            setLineNumber(1)
            setColumnNumber(1)
            setLastLineNumber(1)
            setLastColumnNumber(10)
        }
        val uri = URI.create("file:///test.groovy")

        val constructorSymbol = Symbol.Method.from(constructorNode, uri)

        val documentSymbol = constructorSymbol.toDocumentSymbol()
        assertNotNull(documentSymbol)
        require(documentSymbol != null)
        assertEquals("Widget", documentSymbol.name)
        assertEquals(SymbolKind.Constructor, documentSymbol.kind)
        assertEquals("public Widget(String name)", documentSymbol.detail)
        assertEquals("public Widget(String name)", constructorSymbol.signature)
    }
}
