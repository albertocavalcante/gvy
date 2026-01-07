package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovyparser.ast.types.Position
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class GlobalClassResolutionStrategyTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var strategy: GlobalClassResolutionStrategy

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        strategy = GlobalClassResolutionStrategy(compilationService)
    }

    @AfterEach
    fun tearDown() {
        compilationService.clearCaches()
    }

    @Test
    fun `should resolve cross-file class definition`() = runTest {
        // Arrange: Calculator.groovy defines com.example.Calculator
        val calculatorUri = URI.create("file:///test/com/example/Calculator.groovy")
        val calculatorContent = """
            package com.example
            class Calculator {
                Calculator(int x) {}
            }
        """.trimIndent()

        // Arrange: Main.groovy uses Calculator
        val mainUri = URI.create("file:///test/com/example/Main.groovy")
        val mainContent = """
            package com.example
            class Main {
                void run() {
                    new Calculator(10)
                }
            }
        """.trimIndent()

        // Compile both files to populate index and AST cache
        compilationService.compile(calculatorUri, calculatorContent)
        compilationService.compile(mainUri, mainContent)

        // Get the ConstructorCallExpression from Main.groovy
        val mainAst = compilationService.getAst(mainUri)!!
        val context = ResolutionContext(
            targetNode = findConstructorCall(mainAst),
            documentUri = mainUri,
            position = Position(4, 13), // "Calculator" in "new Calculator(10)"
        )

        // Act: Resolve using GlobalClassResolutionStrategy
        val result = strategy.resolve(context)

        // Assert: Should resolve to Calculator.groovy
        assertTrue(result.isRight(), "Resolution should succeed but failed")
        result.fold(
            ifLeft = { error -> throw AssertionError("Expected Right but got Left: ${error.reason}") },
            ifRight = { definitionResult ->
                val sourceResult =
                    assertInstanceOf(DefinitionResolver.DefinitionResult.Source::class.java, definitionResult)
                assertEquals(calculatorUri, sourceResult.uri, "Should resolve to Calculator.groovy")
                assertEquals("com.example.Calculator", (sourceResult.node as ClassNode).name)
            },
        )
    }

    private fun findConstructorCall(node: ASTNode): ASTNode {
        if (node is ConstructorCallExpression) return node
        if (node is ModuleNode) {
            return node.classes
                .flatMap { it.methods }
                .flatMap { findConstructorCallsInMethod(it) }
                .first { it.type.nameWithoutPackage == "Calculator" }
        }
        throw IllegalArgumentException("Could not find ConstructorCallExpression for 'Calculator'")
    }

    private fun findConstructorCallsInMethod(method: MethodNode): List<ConstructorCallExpression> {
        val result = mutableListOf<ConstructorCallExpression>()
        val visitor = object : CodeVisitorSupport() {
            override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
                result.add(call)
                super.visitConstructorCallExpression(call)
            }
        }
        method.code.visit(visitor)
        return result
    }
}
