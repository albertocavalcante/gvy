// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpockDataVariablesTest {
    @Test
    fun `extracts table headers without treating variable-valued rows as declarations`() {
        val method =
            parseMethod(
                """
            expect:
            left + right == result
            where:
            left | right || result
            1    | 2     || 3
            x    | y     || z
                """.trimIndent(),
            )

        val variables = SpockDataVariables.extract(method)

        assertEquals(listOf("left", "right", "result"), variables.keys.toList())
        assertEquals(7, variables.getValue("left").lineNumber)
        assertEquals(9, variables.getValue("left").columnNumber)
        assertEquals(16, variables.getValue("right").columnNumber)
    }

    @Test
    fun `extracts data pipes destructuring and derived variables across and blocks`() {
        val method =
            parseMethod(
                """
            given:
            def local = 9
            expect:
            a + b == total
            where:
            [a, _, b] << [[1, 99, 2]]
            and:
            total = a + b
            other << [true, false]
                """.trimIndent(),
            )

        assertEquals(listOf("a", "b", "total", "other"), SpockDataVariables.extract(method).keys.toList())
    }

    @Test
    fun `new table after and has independent headers and ignores underscore columns`() {
        val method =
            parseMethod(
                """
            expect:
            true
            where:
            a | _
            1 | 2
            and:
            b || c
            3 || 4
                """.trimIndent(),
            )

        assertEquals(listOf("a", "b", "c"), SpockDataVariables.extract(method).keys.toList())
    }

    @Test
    fun `does not infer data variables from helpers or expressions outside where`() {
        val method =
            parseMethod(
                """
            expect:
            a | b
            x << [1, 2]
            y = 3
                """.trimIndent(),
            )

        assertTrue(SpockDataVariables.extract(method).isEmpty())
    }

    @Test
    fun `underscore separators start another table and filter ends data declarations`() {
        val method =
            parseMethod(
                """
            expect:
            true
            where:
            a | b
            1 | 2
            __
            c | d
            3 | 4
            filter:
            other = true
                """.trimIndent(),
            )

        assertEquals(listOf("a", "b", "c", "d"), SpockDataVariables.extract(method).keys.toList())
    }

    @Test
    fun `extracts only roots from unroll property and method expressions with exact offsets`() {
        val pattern = "#person.name #value.size() #iterationIndex #missing"
        val placeholders = SpockDataVariables.placeholders(pattern)

        assertEquals(listOf("person", "value", "iterationIndex", "missing"), placeholders.map { it.name })
        assertEquals(1, placeholders.first().startOffset)
        assertEquals(7, placeholders.first().endOffset)
        assertEquals("person", pattern.substring(placeholders.first().startOffset, placeholders.first().endOffset))
    }

    @Test
    fun `annotation pattern overrides method name and builtins are known`() {
        val method = parseMethod(
            """
                expect:
                true
                where:
                person << ['Ada']
            """.trimIndent(),
            name = "#unused feature",
            annotation = "@spock.lang.Unroll('#person.name #missing #featureName #iterationIndex " +
                "#dataVariables #dataVariablesWithIndex')",
        )

        assertEquals(listOf("missing"), SpockDataVariables.unknownPlaceholders(method).map { it.name })
    }

    @Test
    fun `uses feature name when unroll has no explicit pattern`() {
        val method = parseMethod(
            """
                expect:
                true
                where:
                value << [1]
            """.trimIndent(),
            name = "#value #missing",
            annotation = "@spock.lang.Unroll",
        )

        assertEquals(listOf("#value #missing"), SpockDataVariables.unrollPatterns(method))
        assertEquals(listOf("missing"), SpockDataVariables.unknownPlaceholders(method).map { it.name })
    }

    @Test
    fun `declines an oversized table header without returning partial declarations`() {
        var header: Expression = VariableExpression("first")
        repeat(5_000) { index ->
            header = BinaryExpression(header, Token.newSymbol(Types.BITWISE_OR, -1, -1), VariableExpression("c$index"))
        }

        val method = methodWithDeclaration(header)
        val nextDeclaration = BinaryExpression(
            VariableExpression("survivor"),
            Token.newSymbol(Types.LEFT_SHIFT, -1, -1),
            ListExpression(),
        )
        (method.code as BlockStatement).addStatement(ExpressionStatement(nextDeclaration))

        assertEquals(listOf("survivor"), SpockDataVariables.extract(method).keys.toList())
    }

    @Test
    fun `declines deeply nested data pipe targets without overflowing the stack`() {
        var target: Expression = VariableExpression("first")
        repeat(5_000) {
            target = ListExpression(listOf(target))
        }
        val declaration = BinaryExpression(target, Token.newSymbol(Types.LEFT_SHIFT, -1, -1), ListExpression())

        assertTrue(SpockDataVariables.extract(methodWithDeclaration(declaration)).isEmpty())
    }

    @Test
    fun `declines oversized data pipe target lists without returning partial declarations`() {
        val target = ListExpression(List(5_000) { VariableExpression("c$it") })
        val declaration = BinaryExpression(target, Token.newSymbol(Types.LEFT_SHIFT, -1, -1), ListExpression())

        assertTrue(SpockDataVariables.extract(methodWithDeclaration(declaration)).isEmpty())
    }

    private fun methodWithDeclaration(expression: Expression): MethodNode = parseMethod("expect: true").apply {
        // Construct adversarial ASTs directly so this checks the analyzer independently of parser limits.
        code = BlockStatement().apply {
            addStatement(ExpressionStatement(expression).apply { addStatementLabel("where") })
        }
    }

    private fun parseMethod(body: String, name: String = "feature", annotation: String = ""): MethodNode {
        val content = "class ExampleSpec extends spock.lang.Specification {\n" +
            "    $annotation\n" +
            "    def \"$name\"() {\n" +
            "${body.prependIndent("        ")}\n" +
            "    }\n}"
        val result = GroovyParserFacade().parse(
            ParseRequest(URI.create("file:///ExampleSpec.groovy"), content, compilePhase = Phases.CONVERSION),
        )
        val method = result.ast?.classes?.flatMap { it.methods }?.find { it.name == name }
        return assertNotNull(method, "Expected conversion AST for: $content")
    }
}
