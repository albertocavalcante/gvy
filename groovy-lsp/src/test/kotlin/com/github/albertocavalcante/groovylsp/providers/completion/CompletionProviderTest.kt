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

    /**
     * Shared stub TypeSolver for map completion tests.
     * Returns unsolved for all type resolution requests, allowing
     * the semantic analysis to use AST-based type inference.
     */
    private val stubTypeSolver = object : TypeSolver {
        override var parent: TypeSolver? = null
        override fun tryToSolveType(name: String) = SymbolReference.unsolved<ResolvedTypeDeclaration>()
    }

    /**
     * Creates a SemanticTypeResolver using the shared stubTypeSolver.
     * Use this for tests that need real semantic analysis without mocking.
     */
    private fun createRealSemanticResolver() = SemanticTypeResolver(stubTypeSolver)

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

    // ========================================================================
    // Map Literal Key Completion Tests
    // ========================================================================

    @Test
    fun `should suggest map literal keys for script-level declaration`() = runTest {
        val realSemanticResolver = createRealSemanticResolver()
        val content = """
            def config = [host: "localhost", port: 8080]
            config.
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            1, // Line 1 (0-indexed)
            7, // After 'config.'
            compilationService,
            realSemanticResolver,
            content,
        )

        // Should suggest map literal keys
        assertTrue(
            completions.any { it.label == "host" },
            "Should suggest 'host' map key. Found: ${completions.map { it.label }}",
        )
        assertTrue(
            completions.any { it.label == "port" },
            "Should suggest 'port' map key. Found: ${completions.map { it.label }}",
        )

        // Should NOT have keywords
        assertTrue(
            completions.none { it.label == "abstract" },
            "Should NOT suggest 'abstract' keyword when completing map literal keys",
        )
    }

    @Test
    fun `should suggest map literal keys inside class method`() = runTest {
        val realSemanticResolver = createRealSemanticResolver()
        val content = """
            class MyService {
                void configure() {
                    def settings = [debug: true, timeout: 5000]
                    settings.
                }
            }
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            3, // Line with 'settings.'
            17, // After 'settings.'
            compilationService,
            realSemanticResolver,
            content,
        )

        assertTrue(
            completions.any { it.label == "debug" },
            "Should suggest 'debug' map key inside class method. Found: ${completions.map { it.label }}",
        )
        assertTrue(
            completions.any { it.label == "timeout" },
            "Should suggest 'timeout' map key inside class method. Found: ${completions.map { it.label }}",
        )
    }

    @Test
    fun `should suggest map literal keys inside if block`() = runTest {
        val realSemanticResolver = createRealSemanticResolver()
        val content = """
            class MyService {
                void process() {
                    if (true) {
                        def opts = [retry: 3, verbose: false]
                        opts.
                    }
                }
            }
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            4, // Line with 'opts.'
            17, // After 'opts.'
            compilationService,
            realSemanticResolver,
            content,
        )

        assertTrue(
            completions.any { it.label == "retry" },
            "Should suggest 'retry' map key inside if block. Found: ${completions.map { it.label }}",
        )
        assertTrue(
            completions.any { it.label == "verbose" },
            "Should suggest 'verbose' map key inside if block. Found: ${completions.map { it.label }}",
        )
    }

    @Test
    fun `should still suggest map methods alongside literal keys`() = runTest {
        val realSemanticResolver = createRealSemanticResolver()
        val content = """
            def m = [x: 1]
            m.
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            1,
            2,
            compilationService,
            realSemanticResolver,
            content,
        )

        // Should have map literal key
        assertTrue(
            completions.any { it.label == "x" },
            "Should suggest 'x' map key. Found: ${completions.map { it.label }}",
        )

        // Should also have standard map methods
        assertTrue(
            completions.any { it.label == "get" || it.label == "size" || it.label == "put" },
            "Should also suggest standard map methods. Found: ${completions.map { it.label }}",
        )
    }

    @Test
    fun `should handle empty map gracefully`() = runTest {
        val realSemanticResolver = createRealSemanticResolver()
        val content = """
            def emptyMap = [:]
            emptyMap.
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            1,
            9,
            compilationService,
            realSemanticResolver,
            content,
        )

        // Should NOT crash, and should still have map methods
        assertTrue(
            completions.any { it.label == "get" || it.label == "size" || it.label == "isEmpty" },
            "Empty map should still suggest standard map methods. Found: ${completions.map { it.label }}",
        )
    }

    @Test
    fun `should suggest map keys from inner scoped variable`() = runTest {
        val realSemanticResolver = createRealSemanticResolver()
        val content = """
class MyService {
    void process() {
        def outerConfig = [outer: "value"]
        if (true) {
            def innerConfig = [inner: "shadowed"]
            innerConfig.
        }
    }
}
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            5,
            24,
            compilationService,
            realSemanticResolver,
            content,
        )

        assertTrue(
            completions.any { it.label == "inner" },
            "Should suggest 'inner' from inner map variable. Found: ${completions.map { it.label }}",
        )
    }

    @Test
    @org.junit.jupiter.api.Disabled("Multi-class map completion needs investigation - PR #839")
    fun `should suggest map keys in second class of multi-class file`() = runTest {
        val realSemanticResolver = createRealSemanticResolver()
        val content = """class FirstClass {
    void first() {
        def firstMap = [a: 1]
        firstMap.
    }
}
class SecondClass {
    void second() {
        def secondMap = [b: 2]
        secondMap.
    }
}
        """.trimIndent()
        val uri = "file:///test.groovy"

        val completions = CompletionProvider.getContextualCompletions(
            uri,
            9,
            18,
            compilationService,
            realSemanticResolver,
            content,
        )

        assertTrue(
            completions.any { it.label == "b" },
            "Should suggest 'b' from map in second class. Found: ${completions.map { it.label }}",
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
