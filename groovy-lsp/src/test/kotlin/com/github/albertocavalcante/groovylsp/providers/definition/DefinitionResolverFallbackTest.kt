package com.github.albertocavalcante.groovylsp.providers.definition

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.types.Position
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for DefinitionResolver fallback behavior and SourceNavigator integration.
 *
 * Key behaviors tested:
 * - Source navigation returns extracted .java file URI (success path)
 * - Source navigation fallback when source JAR unavailable
 * - Graceful error handling when source resolution fails
 *
 * Note on JDK classes (jrt: URIs) and external libraries (jar: URIs):
 * These tests verify that when source navigation succeeds, we return the extracted source URI.
 * When it fails, the resolver's normal resolution path takes over.
 *
 * TODO: Future enhancement - resolve JDK sources from $JAVA_HOME/lib/src.zip
 * FIXME: jar: and jrt: URIs currently cannot be resolved directly by VS Code
 */
class DefinitionResolverFallbackTest {

    /**
     * Creates a ClassNode with valid position info for testing.
     */
    private fun createPositionedClassNode(className: String, line: Int = 1, col: Int = 1): ClassNode {
        val node = ClassNode(className, 0, null)
        node.lineNumber = line
        node.columnNumber = col
        node.lastLineNumber = line
        node.lastColumnNumber = col + 10
        return node
    }

    private fun buildUri(vararg parts: String): URI = URI.create(parts.joinToString(separator = ""))

    @Nested
    inner class SourceNavigationSuccessTest {
        /**
         * When SourceNavigator successfully extracts source,
         * we should return the file: URI to the extracted .java file.
         */
        @Test
        fun `Apache Commons StringUtils navigates to extracted source`() {
            val documentUri = URI.create("file:///project/src/TextProcessor.groovy")
            val position = Position(6, 25)

            val astVisitor = mockk<GroovyAstModel>()
            val symbolTable = mockk<SymbolTable>()
            val compilationService = mockk<GroovyCompilationService>(relaxed = true)
            val sourceNavigationService = mockk<SourceNavigator>()

            val targetNode = createPositionedClassNode("org.apache.commons.lang3.StringUtils", 6, 25)
            every { astVisitor.getNodeAt(documentUri, position) } returns targetNode
            every { astVisitor.getAllClassNodes() } returns emptyList()
            every { compilationService.getAllSymbolStorages() } returns emptyMap()
            every { compilationService.getAst(documentUri) } returns null

            val jarUri = buildUri(
                "jar:file:///home/user/.m2/repository/org/apache/commons/commons-lang3/3.12.0/",
                "commons-lang3-3.12.0.jar!/org/apache/commons/lang3/StringUtils.class",
            )
            val extractedSourceUri = buildUri(
                "file:///home/user/.gls/cache/extracted-sources/org/apache/commons/lang3/",
                "StringUtils.java",
            )

            every { compilationService.findClasspathClass("org.apache.commons.lang3.StringUtils") } returns jarUri

            // Source JAR downloaded and extracted successfully
            coEvery {
                sourceNavigationService.navigateToSource(jarUri, "org.apache.commons.lang3.StringUtils")
            } returns SourceNavigator.SourceResult.SourceLocation(
                uri = extractedSourceUri,
                className = "org.apache.commons.lang3.StringUtils",
            )

            val resolver = DefinitionResolver(astVisitor, symbolTable, compilationService, sourceNavigationService)
            val result = runBlocking { resolver.findDefinitionAt(documentUri, position) }

            // file: URI can be opened by VS Code
            assertTrue(result is DefinitionResolver.DefinitionResult.Binary)
            assertEquals(extractedSourceUri, (result as DefinitionResolver.DefinitionResult.Binary).uri)
            assertEquals("org.apache.commons.lang3.StringUtils", result.name)
        }

        /**
         * Verify SourceNavigator is called with correct parameters
         */
        @Test
        fun `SourceNavigator is invoked with jar URI and class name`() {
            val documentUri = URI.create("file:///project/src/Main.groovy")
            val position = Position(5, 10)

            val astVisitor = mockk<GroovyAstModel>()
            val symbolTable = mockk<SymbolTable>()
            val compilationService = mockk<GroovyCompilationService>(relaxed = true)
            val sourceNavigationService = mockk<SourceNavigator>()

            val targetNode = createPositionedClassNode("org.slf4j.Logger", 5, 10)
            every { astVisitor.getNodeAt(documentUri, position) } returns targetNode
            every { astVisitor.getAllClassNodes() } returns emptyList()
            every { compilationService.getAllSymbolStorages() } returns emptyMap()
            every { compilationService.getAst(documentUri) } returns null

            val jarUri = buildUri(
                "jar:file:///home/user/.m2/repository/org/slf4j/slf4j-api/2.0.9/",
                "slf4j-api-2.0.9.jar!/org/slf4j/Logger.class",
            )
            val sourceUri = buildUri(
                "file:///home/user/.gls/cache/extracted-sources/org/slf4j/",
                "Logger.java",
            )
            every { compilationService.findClasspathClass("org.slf4j.Logger") } returns jarUri

            coEvery {
                sourceNavigationService.navigateToSource(jarUri, "org.slf4j.Logger")
            } returns SourceNavigator.SourceResult.SourceLocation(
                uri = sourceUri,
                className = "org.slf4j.Logger",
            )

            val resolver = DefinitionResolver(astVisitor, symbolTable, compilationService, sourceNavigationService)
            runBlocking { resolver.findDefinitionAt(documentUri, position) }

            // Verify the service was called with correct parameters
            io.mockk.coVerify {
                sourceNavigationService.navigateToSource(jarUri, "org.slf4j.Logger")
            }
        }
    }

    @Nested
    inner class FallbackBehaviorTest {

        @Test
        fun `fallbacks to binary URI when source navigation returns BinaryOnly`() {
            val documentUri = URI.create("file:///project/src/App.groovy")
            val position = Position(10, 10)

            val astVisitor = mockk<GroovyAstModel>()
            val symbolTable = mockk<SymbolTable>()
            val compilationService = mockk<GroovyCompilationService>(relaxed = true)
            val sourceNavigationService = mockk<SourceNavigator>()

            val targetNode = createPositionedClassNode("com.example.Lib", 10, 10)
            every { astVisitor.getNodeAt(documentUri, position) } returns targetNode
            every { astVisitor.getAllClassNodes() } returns emptyList()
            every { compilationService.getAllSymbolStorages() } returns emptyMap()
            every { compilationService.getAst(documentUri) } returns null

            val jarUri = URI.create("jar:file:///libs/lib.jar!/com/example/Lib.class")
            every { compilationService.findClasspathClass("com.example.Lib") } returns jarUri

            // Service returns BinaryOnly
            coEvery {
                sourceNavigationService.navigateToSource(jarUri, "com.example.Lib")
            } returns SourceNavigator.SourceResult.BinaryOnly(
                uri = jarUri,
                className = "com.example.Lib",
                reason = "No source found",
            )

            val resolver = DefinitionResolver(astVisitor, symbolTable, compilationService, sourceNavigationService)
            val result = runBlocking { resolver.findDefinitionAt(documentUri, position) }

            // Should fallback to returning null for jar: URI (since VS Code can't open it)
            // Note: Current DefinitionResolver logic returns null for "jar" scheme in fallback block
            // See DefinitionResolver.kt:285
            assertEquals(null, result)
        }

        @Test
        fun `fallbacks to binary URI when source navigation throws exception`() {
            val documentUri = URI.create("file:///project/src/App.groovy")
            val position = Position(10, 10)

            val astVisitor = mockk<GroovyAstModel>()
            val symbolTable = mockk<SymbolTable>()
            val compilationService = mockk<GroovyCompilationService>(relaxed = true)
            val sourceNavigationService = mockk<SourceNavigator>()

            val targetNode = createPositionedClassNode("com.example.Lib", 10, 10)
            every { astVisitor.getNodeAt(documentUri, position) } returns targetNode
            every { astVisitor.getAllClassNodes() } returns emptyList()
            every { compilationService.getAllSymbolStorages() } returns emptyMap()
            every { compilationService.getAst(documentUri) } returns null

            val jarUri = URI.create("jar:file:///libs/lib.jar!/com/example/Lib.class")
            every { compilationService.findClasspathClass("com.example.Lib") } returns jarUri

            // Service throws exception
            coEvery {
                sourceNavigationService.navigateToSource(jarUri, "com.example.Lib")
            } throws RuntimeException("Network error")

            val resolver = DefinitionResolver(astVisitor, symbolTable, compilationService, sourceNavigationService)
            val result = runBlocking { resolver.findDefinitionAt(documentUri, position) }

            // Should catch exception and fallback
            assertEquals(null, result)
        }

        @Test
        fun `works correctly when SourceNavigator is null`() {
            val documentUri = URI.create("file:///project/src/App.groovy")
            val position = Position(10, 10)

            val astVisitor = mockk<GroovyAstModel>()
            val symbolTable = mockk<SymbolTable>()
            val compilationService = mockk<GroovyCompilationService>(relaxed = true)

            val targetNode = createPositionedClassNode("com.example.Lib", 10, 10)
            every { astVisitor.getNodeAt(documentUri, position) } returns targetNode
            every { astVisitor.getAllClassNodes() } returns emptyList()
            every { compilationService.getAllSymbolStorages() } returns emptyMap()
            every { compilationService.getAst(documentUri) } returns null

            val jarUri = URI.create("jar:file:///libs/lib.jar!/com/example/Lib.class")
            every { compilationService.findClasspathClass("com.example.Lib") } returns jarUri

            // Null service - backward compatibility mode
            val resolver =
                DefinitionResolver(astVisitor, symbolTable, compilationService, sourceNavigator = null)
            val result = runBlocking { resolver.findDefinitionAt(documentUri, position) }

            // No source nav attempted, fall through to binary result
            // Since it's a jar: URI, it returns null
            assertEquals(null, result)
        }
    }

    @Nested
    inner class WorkspaceClassResolutionTest {
        /**
         * Classes defined in the workspace are resolved normally via symbol index
         */
        @Test
        fun `resolves workspace class from symbol index`() {
            val currentUri = URI.create("file:///project/src/Main.groovy")
            val otherUri = URI.create("file:///project/src/utils/DateHelper.groovy")
            val position = Position(10, 5)

            val astVisitor = mockk<GroovyAstModel>()
            val symbolTable = mockk<SymbolTable>()
            val compilationService = mockk<GroovyCompilationService>(relaxed = true)

            val targetNode = createPositionedClassNode("DateHelper", 10, 5)
            every { astVisitor.getNodeAt(currentUri, position) } returns targetNode
            every { astVisitor.getAllClassNodes() } returns emptyList()

            // Class is defined in another workspace file
            val dateHelperNode = createPositionedClassNode("DateHelper", 1, 1)
            val symbol = com.github.albertocavalcante.groovyparser.ast.symbols.Symbol.Class.from(
                dateHelperNode,
                otherUri,
            )
            val symbolIndex = com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex().add(symbol)

            every { compilationService.getAllSymbolStorages() } returns mapOf(otherUri to symbolIndex)
            every { compilationService.getAst(currentUri) } returns null

            val otherAst = mockk<org.codehaus.groovy.ast.ModuleNode>()
            every { compilationService.getAst(otherUri) } returns otherAst
            every { otherAst.classes } returns listOf(dateHelperNode)

            val resolver = DefinitionResolver(astVisitor, symbolTable, compilationService)
            val result = runBlocking { resolver.findDefinitionAt(currentUri, position) }

            assertTrue(result is DefinitionResolver.DefinitionResult.Source)
            val sourceResult = result as DefinitionResolver.DefinitionResult.Source
            assertEquals(otherUri, sourceResult.uri)
            assertEquals(dateHelperNode, sourceResult.node)
        }
    }

    @Nested
    inner class ImportNodeResolutionTest {
        /**
         * Import nodes for external classes should trigger classpath lookup
         * and source navigation when available.
         */
        @Test
        fun `import node triggers source navigation and returns extracted source`() {
            val documentUri = URI.create("file:///project/src/App.groovy")
            val position = Position(3, 20)

            val astVisitor = mockk<GroovyAstModel>()
            val symbolTable = mockk<SymbolTable>()
            val compilationService = mockk<GroovyCompilationService>(relaxed = true)
            val sourceNavigationService = mockk<SourceNavigator>()

            // Create import node: import com.google.gson.Gson
            val importedType = createPositionedClassNode("com.google.gson.Gson", 3, 8)
            val importNode = ImportNode(importedType, null).apply {
                lineNumber = 3
                columnNumber = 1
                lastLineNumber = 3
                lastColumnNumber = 30
            }

            every { astVisitor.getNodeAt(documentUri, position) } returns importNode
            every { astVisitor.getAllClassNodes() } returns emptyList()
            every { compilationService.getAllSymbolStorages() } returns emptyMap()

            val jarUri = buildUri(
                "jar:file:///home/user/.m2/repository/com/google/code/gson/gson/2.10.1/",
                "gson-2.10.1.jar!/com/google/gson/Gson.class",
            )
            val extractedSourceUri = buildUri(
                "file:///home/user/.gls/cache/extracted-sources/com/google/gson/",
                "Gson.java",
            )
            every { compilationService.findClasspathClass("com.google.gson.Gson") } returns jarUri

            coEvery {
                sourceNavigationService.navigateToSource(jarUri, "com.google.gson.Gson")
            } returns SourceNavigator.SourceResult.SourceLocation(
                uri = extractedSourceUri,
                className = "com.google.gson.Gson",
            )

            val resolver = DefinitionResolver(astVisitor, symbolTable, compilationService, sourceNavigationService)
            val result = runBlocking { resolver.findDefinitionAt(documentUri, position) }

            assertTrue(result is DefinitionResolver.DefinitionResult.Binary)
            val binaryResult = result as DefinitionResolver.DefinitionResult.Binary
            assertEquals(extractedSourceUri, binaryResult.uri)
            assertEquals("com.google.gson.Gson", binaryResult.name)
        }
    }
}
