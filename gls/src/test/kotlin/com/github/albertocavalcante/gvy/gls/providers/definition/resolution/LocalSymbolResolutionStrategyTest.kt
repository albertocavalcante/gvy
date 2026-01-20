package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.findNodeAt
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Test for LocalSymbolResolutionStrategy to ensure it correctly handles cross-file class references.
 *
 * This test reproduces the bug where clicking on `new Calculator(10)` in Main.groovy
 * returns Main.groovy instead of Calculator.groovy.
 */
class LocalSymbolResolutionStrategyTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var strategy: LocalSymbolResolutionStrategy

    // URIs for testing
    private val mainUri = URI.create("file:///test/Main.groovy")
    private val calculatorUri = URI.create("file:///test/Calculator.groovy")

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
    }

    @AfterEach
    fun tearDown() {
        compilationService.clearCaches()
    }

    @Test
    fun `should NOT resolve cross-file constructor call in LocalStrategy - full DefinitionResolver integration`() =
        runBlocking {
            // This test reproduces the ACTUAL E2E bug where DefinitionResolver returns Main.groovy
            // instead of Calculator.groovy for `new Calculator(10)`

            // Arrange: Calculator.groovy defines Calculator class
            val calculatorContent = """
            package com.example

            class Calculator {
                int value = 0

                Calculator(int initial) {
                    this.value = initial
                }
            }
            """.trimIndent()

            // Arrange: Main.groovy uses Calculator from another file
            val mainContent = """
            package com.example

            class Main {
                void run() {
                    Calculator calc = new Calculator(10)
                }
            }
            """.trimIndent()

            // Compile both files
            compilationService.compile(calculatorUri, calculatorContent)
            compilationService.compile(mainUri, mainContent)

            // Create a full DefinitionResolver (not just LocalSymbolResolutionStrategy)
            val astModel = compilationService.getAstModel(mainUri)!!
            val symbolTable = compilationService.getSymbolTable(mainUri)!!
            val definitionResolver = DefinitionResolver(
                astVisitor = astModel,
                symbolTable = symbolTable,
                compilationService = compilationService,
                sourceNavigator = null,
                workspaceSymbolIndex = null,
            )

            // Act: Request definition at the constructor call position
            // Line 4: "        Calculator calc = new Calculator(10)"
            //                                       ^-- char 30
            val result = definitionResolver.findDefinitionAt(mainUri, Position(4, 30))

            // Assert: Should resolve to Calculator.groovy, NOT Main.groovy
            // Note: Without WorkspaceSymbolIndex, it may return null (which is acceptable)
            // The key test is that it does NOT return Main.groovy
            if (result == null) {
                println("=== RESULT ===")
                println("Resolved to null (expected without WorkspaceSymbolIndex)")
                // This is OK - GlobalClassResolutionStrategy would handle it with proper setup
            } else if (result is DefinitionResolver.DefinitionResult.Source) {
                println("=== RESULT ===")
                println("Resolved to URI: ${result.uri}")
                println("Resolved to node: ${result.node.javaClass.simpleName}")
                println("Node position: ${result.node.lineNumber}:${result.node.columnNumber}")

                assertTrue(
                    result.uri == calculatorUri,
                    "BUG REPRODUCED! Expected Calculator.groovy but got ${result.uri}. " +
                        "The fix should prevent Main.groovy from being returned!",
                )
            } else if (result is DefinitionResolver.DefinitionResult.Binary) {
                println("Got Binary result: ${result.uri}")
                assertTrue(
                    result.uri == calculatorUri,
                    "BUG REPRODUCED! Expected Calculator.groovy but got ${result.uri}",
                )
            }
        }

    @Test
    fun `should NOT resolve cross-file constructor call - defer to other strategies`() = runBlocking {
        // Arrange: Calculator.groovy defines Calculator class
        val calculatorContent = """
            package com.example

            class Calculator {
                int value = 0

                Calculator(int initial) {
                    this.value = initial
                }
            }
        """.trimIndent()

        // Arrange: Main.groovy uses Calculator from another file
        val mainContent = """
            package com.example

            class Main {
                void run() {
                    Calculator calc = new Calculator(10)
                }
            }
        """.trimIndent()

        // Compile both files so they're in the compilation service
        compilationService.compile(calculatorUri, calculatorContent)

        compilationService.compile(mainUri, mainContent)
        val mainAst = compilationService.getAst(mainUri) as? ModuleNode

        // Get the AST model and symbol table from compilation service
        val astVisitor = compilationService.getAstModel(mainUri)!!
        val symbolTable = compilationService.getSymbolTable(mainUri)!!
        strategy = LocalSymbolResolutionStrategy(astVisitor, symbolTable)

        // Find the ConstructorCallExpression at position (4, 30)
        // Line 4: "        Calculator calc = new Calculator(10)"
        //                                       ^-- char 30
        val targetNode = mainAst!!.findNodeAt(4, 30)
        assertInstanceOf(
            ConstructorCallExpression::class.java,
            targetNode,
            "Expected ConstructorCallExpression at position (4, 30)",
        )

        val constructorCall = targetNode as ConstructorCallExpression

        // Debug: Check what the ClassNode looks like
        val targetClass = constructorCall.type
        println("=== DEBUG ===")
        println("ConstructorCallExpression.type.name: ${targetClass.name}")
        println("ConstructorCallExpression.type URI from astVisitor: ${astVisitor.getUri(targetClass)}")
        println("ConstructorCallExpression.type position: ${targetClass.lineNumber}:${targetClass.columnNumber}")
        println("ConstructorCallExpression.type redirect URI: ${astVisitor.getUri(targetClass.redirect())}")
        println(
            "ConstructorCallExpression.type redirect position: " +
                "${targetClass.redirect().lineNumber}:${targetClass.redirect().columnNumber}",
        )
        println("All classes in AST model:")
        astVisitor.getAllClassNodes().forEach { cls ->
            println("  - ${cls.name} at ${astVisitor.getUri(cls)} position ${cls.lineNumber}:${cls.columnNumber}")
        }

        // Act: Try to resolve with LocalSymbolResolutionStrategy
        val context = ResolutionContext(
            targetNode = constructorCall,
            documentUri = mainUri,
            position = Position(4, 30),
        )

        val result = strategy.resolve(context)

        println("Resolution result: ${if (result.isLeft()) "Left" else "Right"}")
        result.fold(
            ifLeft = { error -> println("Error: ${error.source} - ${error.reason}") },
            ifRight = { println("Success: $it") },
        )

        // Assert: Should return Left (not found) because Calculator is NOT defined in Main.groovy
        // This allows the pipeline to continue to SemanticDB or GlobalClass strategies
        assertTrue(
            result.isLeft(),
            "CRITICAL BUG: LocalSymbolResolutionStrategy should return Left for cross-file class, " +
                "but returned Right. This causes it to incorrectly resolve to Main.groovy!",
        )

        result.fold(
            ifLeft = { error ->
                assertEquals("LocalSymbol", error.source)
                // The reason can be "No local definition found" or mention "Calculator" or "external"
                // Both are acceptable as long as it returns Left (not found)
            },
            ifRight = { definitionResult ->
                // If we get here, the bug is present - check what URI it resolved to
                if (definitionResult is DefinitionResolver.DefinitionResult.Source) {
                    throw AssertionError(
                        "BUG REPRODUCED! LocalSymbolResolutionStrategy incorrectly resolved to " +
                            "${definitionResult.uri}. Expected Left (not found), but got Right. " +
                            "For cross-file class Calculator, it should defer to SemanticDB/GlobalClass strategies.",
                    )
                }
            },
        )
    }

    @Test
    fun `should resolve same-file constructor call`() = runBlocking {
        // Arrange: Single file with nested class
        val content = """
            package com.example

            class Outer {
                class Inner {
                    Inner() {}
                }

                void test() {
                    Inner inner = new Inner()
                }
            }
        """.trimIndent()

        compilationService.compile(mainUri, content)
        val ast = compilationService.getAst(mainUri) as? ModuleNode

        val astVisitor = compilationService.getAstModel(mainUri)!!
        val symbolTable = compilationService.getSymbolTable(mainUri)!!
        strategy = LocalSymbolResolutionStrategy(astVisitor, symbolTable)

        // Find the ConstructorCallExpression for "new Inner()"
        val targetNode = ast!!.findNodeAt(8, 26) // Position of "new Inner()"
        assertInstanceOf(
            ConstructorCallExpression::class.java,
            targetNode,
            "Expected ConstructorCallExpression",
        )

        val constructorCall = targetNode as ConstructorCallExpression

        // Act: Try to resolve with LocalSymbolResolutionStrategy
        val context = ResolutionContext(
            targetNode = constructorCall,
            documentUri = mainUri,
            position = Position(8, 26),
        )

        val result = strategy.resolve(context)

        // Assert: Should resolve to Inner class in the same file
        result.fold(
            ifLeft = { error ->
                throw AssertionError(
                    "Expected Right (found) for same-file class, got Left: ${error.source} - ${error.reason}",
                )
            },
            ifRight = { definitionResult ->
                assertInstanceOf(
                    DefinitionResolver.DefinitionResult.Source::class.java,
                    definitionResult,
                )
                val sourceResult = definitionResult as DefinitionResolver.DefinitionResult.Source
                assertEquals(mainUri, sourceResult.uri, "Should resolve to same file")
            },
        )
    }

    @Test
    fun `should NOT resolve constructor for external class with no AST tracking`() = runBlocking {
        // Arrange: Main.groovy uses Calculator, but Calculator.groovy is NOT compiled/tracked
        val mainContent = """
            package com.example

            class Main {
                void run() {
                    Calculator calc = new Calculator(10)
                }
            }
        """.trimIndent()

        compilationService.compile(mainUri, mainContent)
        val mainAst = compilationService.getAst(mainUri) as? ModuleNode

        val astVisitor = compilationService.getAstModel(mainUri)!!
        val symbolTable = compilationService.getSymbolTable(mainUri)!!
        strategy = LocalSymbolResolutionStrategy(astVisitor, symbolTable)

        // Find the ConstructorCallExpression
        val targetNode = mainAst!!.findNodeAt(4, 30)
        assertInstanceOf(ConstructorCallExpression::class.java, targetNode)

        val constructorCall = targetNode as ConstructorCallExpression

        // Act: Try to resolve
        val context = ResolutionContext(
            targetNode = constructorCall,
            documentUri = mainUri,
            position = Position(4, 30),
        )

        val result = strategy.resolve(context)

        // Assert: Should return Left (not found) because Calculator is not tracked in AST
        assertTrue(
            result.isLeft(),
            "Should return Left for untracked external class",
        )
    }
}
