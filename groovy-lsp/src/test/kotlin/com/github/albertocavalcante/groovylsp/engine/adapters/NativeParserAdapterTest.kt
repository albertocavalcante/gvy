package com.github.albertocavalcante.groovylsp.engine.adapters

import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovyparser.api.ParserDiagnostic
import com.github.albertocavalcante.groovyparser.api.ParserPosition
import com.github.albertocavalcante.groovyparser.api.ParserRange
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import io.mockk.mockk
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [NativeParserAdapter].
 */
class NativeParserAdapterTest {

    private lateinit var compilationUnit: CompilationUnit
    private lateinit var sourceUnit: SourceUnit

    @BeforeEach
    fun setUp() {
        compilationUnit = CompilationUnit()
        sourceUnit = SourceUnit("Test.groovy", "", compilationUnit.configuration, null, null)
    }

    @Test
    fun `isSuccessful returns true when no errors`() {
        val moduleNode = createModuleWithClass("Foo")
        val parseResult = createParseResult(moduleNode, emptyList())
        val adapter = NativeParserAdapter(parseResult, "file:///Test.groovy")

        assertEquals(true, adapter.isSuccessful)
    }

    @Test
    fun `diagnostics are converted from parser diagnostics`() {
        val moduleNode = createModuleWithClass("Foo")
        val diagnostic = ParserDiagnostic(
            message = "Test error",
            range = ParserRange(
                start = ParserPosition(1, 0),
                end = ParserPosition(1, 10),
            ),
            severity = ParserSeverity.ERROR,
            source = "test",
            code = "TEST001",
        )
        val parseResult = createParseResult(moduleNode, listOf(diagnostic))
        val adapter = NativeParserAdapter(parseResult, "file:///Test.groovy")

        assertEquals(1, adapter.diagnostics.size)
        assertEquals("Test error", adapter.diagnostics[0].message)
    }

    @Test
    fun `allSymbols returns class with correct name and kind`() {
        val moduleNode = createModuleWithClass("MyClass")
        val parseResult = createParseResult(moduleNode)
        val adapter = NativeParserAdapter(parseResult, "file:///Test.groovy")

        val symbols = adapter.allSymbols()

        assertEquals(1, symbols.size)
        assertEquals("MyClass", symbols[0].name)
        assertEquals(UnifiedNodeKind.CLASS, symbols[0].kind)
    }

    @Test
    fun `allSymbols includes methods as children`() {
        val moduleNode = createModuleWithClassAndMethod("MyClass", "myMethod")
        val parseResult = createParseResult(moduleNode)
        val adapter = NativeParserAdapter(parseResult, "file:///Test.groovy")

        val symbols = adapter.allSymbols()

        assertEquals(1, symbols.size)
        val classSymbol = symbols[0]
        assertEquals("MyClass", classSymbol.name)

        // Should have method as child
        val methodChildren = classSymbol.children.filter { it.kind == UnifiedNodeKind.METHOD }
        assertEquals(1, methodChildren.size)
        assertEquals("myMethod", methodChildren[0].name)
    }

    @Test
    fun `allSymbols includes constructors as children`() {
        val moduleNode = createModuleWithClassAndConstructor("MyClass")
        val parseResult = createParseResult(moduleNode)
        val adapter = NativeParserAdapter(parseResult, "file:///Test.groovy")

        val symbols = adapter.allSymbols()

        assertEquals(1, symbols.size)
        val classSymbol = symbols[0]

        // Should have constructor as child with class name (not <init>)
        val constructorChildren = classSymbol.children.filter { it.kind == UnifiedNodeKind.CONSTRUCTOR }
        assertEquals(1, constructorChildren.size, "Expected 1 constructor child")
        assertEquals("MyClass", constructorChildren[0].name, "Constructor name should be class name, not <init>")
    }

    @Test
    fun `nodeAt returns null for position outside AST`() {
        val moduleNode = createModuleWithClass("MyClass")
        val parseResult = createParseResult(moduleNode)
        val adapter = NativeParserAdapter(parseResult, "file:///Test.groovy")

        // Position outside any node (line 1000)
        val node = adapter.nodeAt(Position(1000, 0))

        assertNull(node)
    }

    // Helper methods

    private fun createModuleWithClass(className: String): ModuleNode {
        val moduleNode = ModuleNode(sourceUnit)
        val classNode = ClassNode(className, Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        classNode.lineNumber = 1
        classNode.columnNumber = 1
        classNode.lastLineNumber = 5
        classNode.lastColumnNumber = 1
        moduleNode.addClass(classNode)
        return moduleNode
    }

    private fun createModuleWithClassAndMethod(className: String, methodName: String): ModuleNode {
        val moduleNode = createModuleWithClass(className)
        val classNode = moduleNode.classes[0]

        val method = MethodNode(
            methodName,
            Modifier.PUBLIC,
            ClassHelper.VOID_TYPE,
            emptyArray(),
            emptyArray(),
            null,
        )
        method.lineNumber = 2
        method.columnNumber = 5
        method.lastLineNumber = 4
        method.lastColumnNumber = 5
        method.declaringClass = classNode
        classNode.addMethod(method)

        return moduleNode
    }

    private fun createModuleWithClassAndConstructor(className: String): ModuleNode {
        val moduleNode = createModuleWithClass(className)
        val classNode = moduleNode.classes[0]

        // Add a constructor (constructors are separate from methods in ClassNode)
        val constructor = ConstructorNode(
            Modifier.PUBLIC,
            emptyArray<Parameter>(),
            emptyArray(),
            null,
        )
        constructor.lineNumber = 2
        constructor.columnNumber = 5
        constructor.lastLineNumber = 3
        constructor.lastColumnNumber = 5
        constructor.declaringClass = classNode
        classNode.addConstructor(constructor)

        return moduleNode
    }

    private fun createParseResult(
        moduleNode: ModuleNode,
        diagnostics: List<ParserDiagnostic> = emptyList(),
    ): ParseResult = ParseResult(
        ast = moduleNode,
        compilationUnit = compilationUnit,
        sourceUnit = sourceUnit,
        diagnostics = diagnostics,
        symbolTable = SymbolTable(),
        astModel = mockk<GroovyAstModel>(relaxed = true),
    )
}
