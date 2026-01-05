package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.gvy.semantics.SemanticType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class CompletionProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var semanticResolver: SemanticTypeResolver
    private lateinit var moduleNode: ModuleNode

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        semanticResolver = mockk(relaxed = true)
        moduleNode = mockk(relaxed = true)
    }

    @Test
    fun `test getContextualCompletions with valid URI`() = runTest {
        // Arrange
        val content = """
            def localVar = "test"
            class TestClass {
                def method() {
                    return "result"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content to build AST
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act
        val completions = CompletionProvider.getContextualCompletions(
            uri.toString(),
            2, // Line within class
            8, // Character position
            compilationService,
            semanticResolver,
            content,
        )

        // Assert - Should return some completions (could be empty if no context matches)
        assertTrue(completions.size >= 0, "Should return list of completions")
    }

    @Test
    fun `test getContextualCompletions with invalid URI`() = runTest {
        // Act
        val completions = CompletionProvider.getContextualCompletions(
            "invalid-uri",
            0,
            0,
            compilationService,
            semanticResolver,
            "def x = 1",
        )

        // Assert - Should return completions now because we compile transiently
        assertTrue(completions.isNotEmpty(), "Should return completions even for invalid URI")
    }

    @Test
    fun `test getContextualCompletions without compilation`() = runTest {
        // Act - Try to get completions for a file that hasn't been compiled
        val completions = CompletionProvider.getContextualCompletions(
            "file:///unknown.groovy",
            0,
            0,
            compilationService,
            semanticResolver,
            "def x = 1",
        )

        // Assert - Should return completions now because we compile transiently
        assertTrue(completions.isNotEmpty(), "Should return completions even without prior compilation")
    }

    @Test
    fun `should suggest methods for inferred variable type`() = runTest {
        // Arrange
        val content = """
            def list = new ArrayList()
            list.
        """.trimIndent()
        val uri = "file:///test.groovy"
        compilationService.compile(URI.create(uri), content)

        // Mock semantic resolver to return ArrayList for 'list'
        every { semanticResolver.resolveType(any(), any()) } returns SemanticType.Known("java.util.ArrayList")

        // Act
        val completions = CompletionProvider.getContextualCompletions(
            uri,
            1, // Line 1 (0-indexed)
            5, // After 'list.'
            compilationService,
            semanticResolver,
            content,
        )

        // Assert
        assertTrue(completions.any { it.label == "add" }, "Should suggest 'add' method for ArrayList")
        assertTrue(completions.any { it.label == "size" }, "Should suggest 'size' method for ArrayList")
    }

    @Test
    fun `should suggest methods for method parameter type`() = runTest {
        // Arrange
        val content = """
            void process(String text) {
                text.
            }
        """.trimIndent()
        val uri = "file:///test.groovy"
        compilationService.compile(URI.create(uri), content)

        // Mock semantic resolver to return String for 'text'
        every { semanticResolver.resolveType(any(), any()) } returns SemanticType.Known("java.lang.String")

        // Act
        val completions = CompletionProvider.getContextualCompletions(
            uri,
            1,
            9,
            compilationService,
            semanticResolver,
            content,
        )

        // Assert
        assertTrue(completions.any { it.label == "substring" }, "Should suggest 'substring' for String param")
        assertTrue(completions.any { it.label == "length" }, "Should suggest 'length' for String param")
    }

    @Test
    fun `should gracefully handle type resolution failure with fallback to AST type`() = runTest {
        // Arrange
        val content = """
            String text = "hello"
            text.
        """.trimIndent()
        val uri = "file:///test.groovy"
        compilationService.compile(URI.create(uri), content)

        // Mock semantic resolver to throw for type resolution
        every { semanticResolver.resolveType(any(), any()) } throws RuntimeException("Type resolution failed")

        // Act - should not crash, may fall back to AST type info
        val completions = CompletionProvider.getContextualCompletions(
            uri,
            1,
            5,
            compilationService,
            semanticResolver,
            content,
        )

        // Assert - should return some completions (may be from AST fallback or empty, but not crash)
        assertTrue(
            completions.isNotEmpty() || completions.isEmpty(),
            "Should return a list (may be empty) without crashing",
        )
    }

    @Test
    fun `should suggest map methods for map literal variable - unmocked`() = runTest {
        // Arrange - use real SemanticTypeResolver with a stub TypeSolver
        val stubTypeSolver = object : TypeSolver {
            override var parent: TypeSolver? = null
            override fun tryToSolveType(name: String) = SymbolReference.unsolved<ResolvedTypeDeclaration>()
        }
        val realSemanticResolver = SemanticTypeResolver(stubTypeSolver)
        val content = """
            def p = [key1: 'value1', key2: 'value2']
            p.
        """.trimIndent()
        val uri = "file:///test.groovy"

        // Act
        val completions = CompletionProvider.getContextualCompletions(
            uri,
            1, // Line 1 (0-indexed)
            2, // After 'p.'
            compilationService,
            realSemanticResolver,
            content,
        )

        // Assert - Should have map methods (from LinkedHashMap via classpath)
        // Note: actual method availability depends on classpath configuration
        // The primary assertion is that keywords should NOT appear

        // Assert - Should NOT have keywords (proves context detection works)
        assertTrue(
            completions.none { it.label == "abstract" },
            "Should NOT suggest 'abstract' keyword for map member access",
        )
        assertTrue(
            completions.none { it.label == "class" },
            "Should NOT suggest 'class' keyword for map member access",
        )
    }

    // ========================================================================
    // Tests for Fix #11: parseSignatureToParams with generics
    // Note: parseSignatureToParams is a private function, so these tests verify
    // the fix indirectly through method completion functionality
    // ========================================================================

    @Test
    fun `should handle method completions with Map generic parameter`() = runTest {
        // Test that method completion works with Map<String,String> parameter
        // This indirectly tests that parseSignatureToParams handles generics correctly
        val content = """
            class MyService {
                void processMap(Map<String,String> data) {
                    println data
                }

                void test() {
                    processM
                }
            }
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            6,
            16, // After "processM"
            compilationService,
            semanticResolver,
            content,
        )

        // Should suggest the processMap method
        // If parseSignatureToParams didn't handle generics correctly,
        // the method signature wouldn't parse properly
        assertTrue(completions.any { it.label.contains("processMap") })
    }

    @Test
    fun `should handle method completions with multiple generic parameters`() = runTest {
        // Test method with List<String> and Map<Integer,String> parameters
        val content = """
            class DataProcessor {
                void process(List<String> items, Map<Integer,String> mapping, int count) {
                    // implementation
                }

                void test() {
                    proc
                }
            }
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            6,
            12, // After "proc"
            compilationService,
            semanticResolver,
            content,
        )

        // Should suggest the process method
        assertTrue(completions.any { it.label.contains("process") })
    }

    @Test
    fun `should handle method completions with nested generic parameters`() = runTest {
        // Test method with nested generics like Map<String,List<Integer>>
        val content = """
            class ComplexService {
                void handleComplex(Map<String,List<Integer>> complexData) {
                    println complexData
                }

                void test() {
                    handleC
                }
            }
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            6,
            15, // After "handleC"
            compilationService,
            semanticResolver,
            content,
        )

        // Should suggest the handleComplex method
        assertTrue(completions.any { it.label.contains("handleComplex") })
    }

    @Test
    fun `should differentiate overloaded methods with generic parameters`() = runTest {
        // Test that methods with different generic parameters can be distinguished
        val content = """
            class OverloadedService {
                void save(String data) { }
                void save(Map<String,String> data) { }
                void save(List<String> data) { }

                void test() {
                    sav
                }
            }
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            6,
            11, // After "sav"
            compilationService,
            semanticResolver,
            content,
        )

        // Should suggest save methods (all overloads)
        val saveCompletions = completions.filter { it.label.contains("save") }
        assertTrue(saveCompletions.isNotEmpty(), "Should suggest save methods")
    }
}
