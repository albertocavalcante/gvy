package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-file test for findNodeAt returning ConstructorCallExpression.
 * This test mirrors the E2E test scenario with two files:
 * - Calculator.groovy (definition)
 * - Main.groovy (usage with constructor call)
 */
class FindNodeAtCrossFileTest {

    private val parserFacade = GroovyParserFacade()

    @Test
    fun `findNodeAt returns ConstructorCallExpression for cross-file constructor in Main groovy`() = runTest {
        val calculatorCode = """
            package com.example

            class Calculator {
                int value = 0
                int add(int a, int b) { return a + b }
                Calculator(int initial) { this.value = initial }
            }
        """.trimIndent()

        val mainCode = """
            package com.example

            class Main {
                void run() {
                    Calculator calc = new Calculator(10)
                    int result = calc.add(5, 3)
                    int v = calc.value
                }
            }
        """.trimIndent()

        // Parse both files
        val calculatorUri = URI.create("file:///test/Calculator.groovy")
        val mainUri = URI.create("file:///test/Main.groovy")

        val calculatorAst = parserFacade.parse(ParseRequest(calculatorUri, calculatorCode)).ast as ModuleNode
        val mainAst = parserFacade.parse(ParseRequest(mainUri, mainCode)).ast as ModuleNode


        // Position at "Calculator" in "new Calculator(10)" - line 4, character 26
        // Line 4: "        Calculator calc = new Calculator(10)"
        //                                       ^-- char 26 is on 'C' of second Calculator
        val nodeAtConstructor = mainAst.findNodeAt(4, 26)
        assertNotNull(nodeAtConstructor, "Should find node at constructor call")


        // CRITICAL: This should be a ConstructorCallExpression
        assertTrue(
            nodeAtConstructor is ConstructorCallExpression,
            "Expected ConstructorCallExpression but got ${nodeAtConstructor.javaClass.simpleName}",
        )

        // Verify it's constructing the Calculator class
        // Verify it's constructing the Calculator class
        val typeName = nodeAtConstructor.type.name
        assertTrue(
            typeName == "Calculator" || typeName == "com.example.Calculator",
            "Expected Calculator or com.example.Calculator but got $typeName",
        )
    }


}
