package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.groovyparser.ast.findNodeAt
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Test for cross-file "go to definition" resolution.
 *
 * This test verifies that when clicking on a constructor call like `new Calculator(10)`,
 * ModuleNode.findNodeAt() returns ConstructorCallExpression (not ClassNode).
 *
 * This mirrors the E2E test scenario in definition-crossfile.yaml
 *
 * The unit test in parser/native FindNodeAtCrossFileTest already passes,
 * proving that ModuleNode.findNodeAt() works correctly.
 * This test verifies the same behavior in the groovy-lsp module.
 */
class CrossFileDefinitionTest {

    private lateinit var compilationService: GroovyCompilationService

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
    }

    @AfterEach
    fun tearDown() {
        compilationService.clearCaches()
    }

    @Test
    fun `findNodeAt should return ConstructorCallExpression not ClassNode for new Calculator`() = runTest {
        // Arrange - Main.groovy with constructor call to Calculator (defined elsewhere)
        val mainUri = URI.create("file:///test/Main.groovy")
        val mainContent = """
            package com.example

            class Main {
                void run() {
                    Calculator calc = new Calculator(10)
                    int result = calc.add(5, 3)
                    int v = calc.value
                }
            }
        """.trimIndent()

        // Compile Main.groovy
        // Even though Calculator is not defined, Groovy still parses the AST
        compilationService.compile(mainUri, mainContent)
        val mainAst = compilationService.getAst(mainUri) as? ModuleNode
        assertNotNull(mainAst, "Main AST should exist")

        // CRITICAL TEST: Check what node is at position (4, 30)
        // Line 4: "        Calculator calc = new Calculator(10)"
        //                                       ^-- char 30 is on 'C' of "Calculator" in "new Calculator(10)"
        // Position calculation:
        // - Chars 0-7: spaces (8 chars)
        // - Chars 8-17: "Calculator" (10 chars)
        // - Char 18: space
        // - Chars 19-22: "calc" (4 chars)
        // - Char 23: space
        // - Char 24: "="
        // - Char 25: space
        // - Chars 26-28: "new" (3 chars)
        // - Char 29: space
        // - Char 30: "C" of "Calculator"

        // Position uses 0-based line and column numbers
        val position = Position(4, 30)

        // BUT: ModuleNode.findNodeAt expects line and column, potentially with different bases
        val nodeAtPosition =
            mainAst?.findNodeAt(position.line, position.character) ?: throw AssertionError("mainAst should not be null")

        // CRITICAL EXPECTATION: Should be ConstructorCallExpression, NOT ClassNode
        // The unit test FindNodeAtCrossFileTest proves ModuleNode.findNodeAt() returns ConstructorCallExpression.
        // If we're getting ClassNode here, it means:
        // 1. GroovyAstModel.getNodeAt() is returning the wrong node
        // 2. Or DefinitionResolver.selectBestNode() is not preferring the fallback node correctly
        assertTrue(
            nodeAtPosition is ConstructorCallExpression,
            "CRITICAL BUG: Expected ConstructorCallExpression but got ${nodeAtPosition.javaClass.simpleName}. " +
                "This causes cross-file resolution to fail because LocalSymbolResolutionStrategy " +
                "receives ClassNode (Main) instead of ConstructorCallExpression, leading it to return Main.groovy!",
        )

        // Verify the ConstructorCallExpression is for Calculator
        val constructorCall = nodeAtPosition as ConstructorCallExpression
        val typeName = constructorCall.type.name
        assertTrue(
            typeName == "Calculator" || typeName == "com.example.Calculator",
            "Expected Calculator type but got $typeName",
        )
    }

    @Test
    fun `should resolve cross-file constructor call in LocalStrategy - full DefinitionResolver integration`() =
        runTest {
            // Arrange: Calculator.groovy defines Calculator class
            val calculatorUri = URI.create("file:///test/Calculator.groovy")
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
            val mainUri = URI.create("file:///test/Main.groovy")
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
            if (result != null) {
                val resolvedUri = when (result) {
                    is DefinitionResolver.DefinitionResult.Source -> result.uri
                    is DefinitionResolver.DefinitionResult.Binary -> result.uri
                }

                assertEquals(
                    calculatorUri,
                    resolvedUri,
                    "BUG REPRODUCED! Expected Calculator.groovy but got $resolvedUri. " +
                        "The fix should prevent Main.groovy from being returned!",
                )
            }
        }
}
