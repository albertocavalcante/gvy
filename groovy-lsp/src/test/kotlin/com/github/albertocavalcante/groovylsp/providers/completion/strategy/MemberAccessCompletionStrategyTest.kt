package com.github.albertocavalcante.groovylsp.providers.completion.strategy

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionContext
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.gvy.semantics.native.SymbolCompletionContext
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class MemberAccessCompletionStrategyTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var strategy: MemberAccessCompletionStrategy
    private lateinit var semanticResolver: SemanticTypeResolver

    /**
     * Shared stub TypeSolver for tests.
     * Returns unsolved for all type resolution requests, allowing
     * the semantic analysis to use AST-based type inference.
     */
    private val stubTypeSolver = object : TypeSolver {
        override var parent: TypeSolver? = null
        override fun tryToSolveType(name: String) = SymbolReference.unsolved<ResolvedTypeDeclaration>()
    }

    /**
     * Creates a SemanticTypeResolver using the shared stubTypeSolver.
     */
    private fun createRealSemanticResolver() = SemanticTypeResolver(stubTypeSolver)

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        strategy = MemberAccessCompletionStrategy(compilationService, null)
        semanticResolver = createRealSemanticResolver()
    }

    // ========================================================================
    // GDK Methods Tests
    // ========================================================================

    @Test
    fun `should add GDK methods for String type`() = runTest {
        // Arrange
        val content = """
            String text = "hello"
            text.${CompletionProvider.DUMMY_IDENTIFIER}
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")

        // Compile and extract context
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 1,
            character = 5,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
        )

        val strategyContext = createStrategyContext(
            ctx,
            CompletionProvider.ContextType.MemberAccess("java.lang.String", "text"),
        )

        // Act
        val completionResult = strategy.complete(strategyContext)

        // Assert
        assertThat(completionResult.isRight()).isTrue()
        completionResult.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                // Should have GDK methods for String like 'reverse', 'capitalize', etc.
                assertThat(items).isNotEmpty()
                // GDK provides extension methods for String
                val hasGdkMethod = items.any { it.label in listOf("reverse", "capitalize", "center") }
                assertThat(hasGdkMethod).isTrue()
            },
        )
    }

    @Test
    fun `should add GDK methods for List type`() = runTest {
        // Arrange
        val content = """
            def myList = []
            myList.${CompletionProvider.DUMMY_IDENTIFIER}
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")

        // Compile and extract context
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 1,
            character = 7,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
        )

        val strategyContext = createStrategyContext(
            ctx,
            CompletionProvider.ContextType.MemberAccess("java.util.List", "myList"),
        )

        // Act
        val completionResult = strategy.complete(strategyContext)

        // Assert
        assertThat(completionResult.isRight()).isTrue()
        completionResult.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                // Should have GDK methods for List
                assertThat(items).isNotEmpty()
                // GDK provides extension methods for List like 'flatten', 'unique', etc.
                val hasGdkMethod = items.any { it.label in listOf("flatten", "unique", "collect") }
                assertThat(hasGdkMethod).isTrue()
            },
        )
    }

    // ========================================================================
    // Classpath Methods Tests
    // ========================================================================

    @Test
    fun `should add classpath methods for resolved types`() = runTest {
        // Arrange
        val content = """
            String text = "hello"
            text.${CompletionProvider.DUMMY_IDENTIFIER}
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")

        // Compile and extract context
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 1,
            character = 5,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
        )

        val strategyContext = createStrategyContext(
            ctx,
            CompletionProvider.ContextType.MemberAccess("java.lang.String", "text"),
        )

        // Act
        val completionResult = strategy.complete(strategyContext)

        // Assert
        assertThat(completionResult.isRight()).isTrue()
        completionResult.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                // Should have JDK String methods
                assertThat(items).isNotEmpty()
                assertThat(items.any { it.label == "substring" }).isTrue()
                assertThat(items.any { it.label == "length" }).isTrue()
                assertThat(items.any { it.label == "toLowerCase" }).isTrue()
            },
        )
    }

    // ========================================================================
    // Map Literal Keys Tests
    // ========================================================================

    @Test
    fun `should add map literal keys when qualifier is map variable`() = runTest {
        // Arrange
        val content = """
            def config = [host: "localhost", port: 8080]
            config.${CompletionProvider.DUMMY_IDENTIFIER}
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")

        // Compile and extract context
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 1,
            character = 7,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
        )

        val strategyContext = createStrategyContext(
            ctx,
            CompletionProvider.ContextType.MemberAccess("java.util.Map", "config"),
        )

        // Act
        val completionResult = strategy.complete(strategyContext)

        // Assert
        assertThat(completionResult.isRight()).isTrue()
        completionResult.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                // Should have map literal keys
                assertThat(items).isNotEmpty()
                assertThat(items.any { it.label == "host" }).isTrue()
                assertThat(items.any { it.label == "port" }).isTrue()
                // Should also have Map methods (get, put, etc.)
                assertThat(items.any { it.label == "get" || it.label == "put" }).isTrue()
            },
        )
    }

    @Test
    fun `should add map literal keys inside class method`() = runTest {
        // Arrange
        val content = """
            class MyService {
                void configure() {
                    def settings = [debug: true, timeout: 5000]
                    settings.${CompletionProvider.DUMMY_IDENTIFIER}
                }
            }
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")

        // Compile and extract context
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 3,
            character = 17,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
        )

        val strategyContext = createStrategyContext(
            ctx,
            CompletionProvider.ContextType.MemberAccess("java.util.LinkedHashMap", "settings"),
        )

        // Act
        val completionResult = strategy.complete(strategyContext)

        // Assert
        assertThat(completionResult.isRight()).isTrue()
        completionResult.fold(
            ifLeft = { throw AssertionError("Expected Right, got Left: $it") },
            ifRight = { items ->
                // Should have map literal keys
                assertThat(items).isNotEmpty()
                assertThat(items.any { it.label == "debug" }).isTrue()
                assertThat(items.any { it.label == "timeout" }).isTrue()
            },
        )
    }

    // ========================================================================
    // Workspace Members Tests (would require workspace index setup)
    // ========================================================================

    @Test
    fun `should add workspace members for cross-file types`() = runTest {
        // Note: This test would require setting up a WorkspaceSymbolIndex
        // For now, we test that the strategy doesn't fail with null workspace index

        // Arrange
        val content = """
            MyCustomClass obj = new MyCustomClass()
            obj.${CompletionProvider.DUMMY_IDENTIFIER}
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")

        // Compile and extract context
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 1,
            character = 4,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
            workspaceSymbolIndex = null, // No workspace index
        )

        val strategyContext = createStrategyContext(
            ctx,
            CompletionProvider.ContextType.MemberAccess("MyCustomClass", "obj"),
        )

        // Act - should not fail even without workspace index
        val completionResult = strategy.complete(strategyContext)

        // Assert - should succeed (might have empty or fallback completions)
        assertThat(completionResult.isRight()).isTrue()
    }

    // ========================================================================
    // Workspace Class FQN Resolution Tests
    // ========================================================================

    @Test
    fun `should resolve workspace class FQNs from imports`() = runTest {
        // Arrange
        val content = """
            import com.example.MyClass

            MyClass obj = new MyClass()
            obj.${CompletionProvider.DUMMY_IDENTIFIER}
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")

        // Compile and extract context
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 3,
            character = 4,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
        )

        val strategyContext = createStrategyContext(
            ctx,
            CompletionProvider.ContextType.MemberAccess("MyClass", "obj"),
        )

        // Act
        val completionResult = strategy.complete(strategyContext)

        // Assert - should resolve successfully even without finding the class
        assertThat(completionResult.isRight()).isTrue()
    }

    @Test
    fun `should fallback to workspace scan for simple names`() = runTest {
        // Arrange
        val content = """
            UnknownClass obj = new UnknownClass()
            obj.${CompletionProvider.DUMMY_IDENTIFIER}
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")

        // Compile and extract context
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 1,
            character = 4,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
        )

        val strategyContext = createStrategyContext(
            ctx,
            CompletionProvider.ContextType.MemberAccess("UnknownClass", "obj"),
        )

        // Act - should not fail, will scan workspace (empty in this test)
        val completionResult = strategy.complete(strategyContext)

        // Assert
        assertThat(completionResult.isRight()).isTrue()
    }

    // ========================================================================
    // Strategy Not Applicable Tests
    // ========================================================================

    @Test
    fun `should return notApplicable for non-MemberAccess context`() = runTest {
        // Arrange
        val content = "def x = 1"
        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 0,
            character = 9,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
        )

        // TypeParameter context instead of MemberAccess
        val strategyContext = createStrategyContext(
            ctx,
            CompletionProvider.ContextType.TypeParameter("Int"),
        )

        // Act
        val completionResult = strategy.complete(strategyContext)

        // Assert - should return notApplicable (Left)
        assertThat(completionResult.isLeft()).isTrue()
    }

    @Test
    fun `should return notApplicable when context type is null`() = runTest {
        // Arrange
        val content = "def x = 1"
        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compileTransient(uri, content)
        val ctx = CompletionContext(
            uri = uri,
            line = 0,
            character = 9,
            ast = result.ast!!,
            astModel = result.astModel,
            tokenIndex = result.tokenIndex,
            compilationService = compilationService,
            content = content,
            semanticResolver = semanticResolver,
            moduleNode = result.ast,
        )

        val strategyContext = createStrategyContext(ctx, null)

        // Act
        val completionResult = strategy.complete(strategyContext)

        // Assert - should return notApplicable (Left)
        assertThat(completionResult.isLeft()).isTrue()
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private fun createStrategyContext(
        baseContext: CompletionContext,
        contextType: CompletionProvider.ContextType?,
    ): CompletionStrategyContext = CompletionStrategyContext(
        baseContext = baseContext,
        symbolContext = SymbolCompletionContext(
            classes = emptyList(),
            methods = emptyList(),
            fields = emptyList(),
            imports = emptyList(),
            variables = emptyList(),
            currentClass = null,
        ),
        nodeAtCursor = null,
        contextType = contextType,
        mode = com.github.albertocavalcante.groovylsp.config.GroovyMode.GROOVY,
        isJenkinsFile = false,
        jenkinsMetadata = null,
        jenkinsBlockContext = null,
    )
}
