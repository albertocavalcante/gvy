package com.github.albertocavalcante.groovylsp.providers.definition

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.types.Position
import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class DefinitionResolverFallbackTest {

    @Test
    fun `findDefinitionAt falls back to classpath lookup when symbol not in workspace`() {
        // Setup mocks
        val uri = URI.create("file:///test/Test.groovy")
        val position = Position(0, 0)

        val astVisitor = mockk<GroovyAstModel>()
        val symbolTable = mockk<SymbolTable>()
        val compilationService = mockk<GroovyCompilationService>(relaxed = true)

        // Mock AST visitor to return a ClassNode at the position
        val targetNode = ClassNode("com.example.ExternalClass", 0, null)
        every { astVisitor.getNodeAt(uri, position) } returns targetNode
        every { astVisitor.getAllClassNodes() } returns emptyList() // Not declared locally

        // Mock symbol table to return nothing (not resolved locally)
        // Actually resolveToDefinition calls symbol table.
        // We can mock resolveToDefinition behavior by mocking what it depends on,
        // or we can just rely on the fact that if it returns the node itself (reference),
        // and it's not in getAllClassNodes, it triggers global lookup.

        // Mock compilationService to return null for global symbol index lookup
        every { compilationService.getAllSymbolStorages() } returns emptyMap()
        every { compilationService.getAst(uri) } returns null

        // Mock compilationService to return a URI for classpath lookup
        val classpathUri = URI.create("jar:file:///libs/lib.jar!/com/example/ExternalClass.class")
        every { compilationService.findClasspathClass("com.example.ExternalClass") } returns classpathUri

        val resolver = DefinitionResolver(astVisitor, symbolTable, compilationService)

        // Execute
        val result = resolver.findDefinitionAt(uri, position)

        // Verify
        assertTrue(result is DefinitionResolver.DefinitionResult.Binary, "Result should be Binary")
        val binaryResult = result as DefinitionResolver.DefinitionResult.Binary
        assertEquals(classpathUri, binaryResult.uri)
        assertEquals("com.example.ExternalClass", binaryResult.name)
    }

    @Test
    fun `findDefinitionAt resolves import nodes via classpath lookup`() {
        val uri = URI.create("file:///test/Test.groovy")
        val position = Position(0, 0)

        val astVisitor = mockk<GroovyAstModel>()
        val symbolTable = mockk<SymbolTable>()
        val compilationService = mockk<GroovyCompilationService>(relaxed = true)

        val importedType = ClassNode("com.example.ExternalClass", 0, null)
        val importNode = ImportNode(importedType, null)

        every { astVisitor.getNodeAt(uri, position) } returns importNode
        every { astVisitor.getAllClassNodes() } returns emptyList()
        every { compilationService.getAllSymbolStorages() } returns emptyMap()

        val classpathUri = URI.create("jar:file:///libs/lib.jar!/com/example/ExternalClass.class")
        every { compilationService.findClasspathClass("com.example.ExternalClass") } returns classpathUri

        val resolver = DefinitionResolver(astVisitor, symbolTable, compilationService)

        val result = resolver.findDefinitionAt(uri, position)

        assertTrue(result is DefinitionResolver.DefinitionResult.Binary, "Result should be Binary")
        val binaryResult = result as DefinitionResolver.DefinitionResult.Binary
        assertEquals(classpathUri, binaryResult.uri)
        assertEquals("com.example.ExternalClass", binaryResult.name)
    }

    @Test
    fun `findDefinitionAt returns correct URI for global source definition`() {
        // Setup mocks
        val currentUri = URI.create("file:///test/Current.groovy")
        val otherUri = URI.create("file:///test/Other.groovy")
        val position = Position(0, 0)

        val astVisitor = mockk<GroovyAstModel>()
        val symbolTable = mockk<SymbolTable>()
        val compilationService = mockk<GroovyCompilationService>(relaxed = true)

        // Mock AST visitor to return a ClassNode (reference)
        val targetNode = ClassNode("OtherClass", 0, null)
        every { astVisitor.getNodeAt(currentUri, position) } returns targetNode
        every { astVisitor.getAllClassNodes() } returns emptyList() // Not declared locally

        // Create real symbol and index
        val otherClassNode = ClassNode("OtherClass", 0, null)
        // Set line/column to valid values to pass validation
        otherClassNode.lineNumber = 1
        otherClassNode.columnNumber = 1
        otherClassNode.lastLineNumber = 1
        otherClassNode.lastColumnNumber = 10

        val symbol = com.github.albertocavalcante.groovyparser.ast.symbols.Symbol.Class.from(otherClassNode, otherUri)
        val symbolIndex = com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex().add(symbol)

        // Mock compilationService to find symbol in other URI
        every { compilationService.getAllSymbolStorages() } returns mapOf(otherUri to symbolIndex)
        every { compilationService.getAst(currentUri) } returns null

        // Mock AST retrieval for other URI
        val otherAst = mockk<org.codehaus.groovy.ast.ModuleNode>()
        every { compilationService.getAst(otherUri) } returns otherAst
        every { otherAst.classes } returns listOf(otherClassNode)

        val resolver = DefinitionResolver(astVisitor, symbolTable, compilationService)

        // Execute
        val result = resolver.findDefinitionAt(currentUri, position)

        // Verify
        assertTrue(result is DefinitionResolver.DefinitionResult.Source, "Result should be Source")
        val sourceResult = result as DefinitionResolver.DefinitionResult.Source
        assertEquals(otherUri, sourceResult.uri, "Should return URI of the file defining the class")
        assertEquals(otherClassNode, sourceResult.node)
    }
}
