package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class RecursiveVisitorGroovyOperatorsTest {

    private val fixture = ParserTestFixture()

    @Test
    fun `range expressions tracked correctly`() {
        val uri = URI.create("file:///range-test.groovy")
        val code = """
            def inclusive = 0..10
            def exclusive = 0..<10
            def charRange = 'a'..'z'
            (0..5).each { println it }
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")

        val astModel = result.astModel
        val rangeExpressions = astModel.getAllNodes().filterIsInstance<RangeExpression>()
        assertTrue(rangeExpressions.size >= 3, "Should have at least 3 range expressions")
        rangeExpressions.forEach { assertNotNull(astModel.getParent(it), "RangeExpression should have a parent") }
    }

    @Test
    fun `elvis operator tracked correctly`() {
        val uri = URI.create("file:///elvis-test.groovy")
        val code = """
            def name = input ?: "default"
            def value = a ?: b ?: c ?: "fallback"
            def result = obj?.field ?: defaultValue
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")

        val astModel = result.astModel
        val elvisExpressions = astModel.getAllNodes().filterIsInstance<ElvisOperatorExpression>()
        assertTrue(elvisExpressions.isNotEmpty(), "Should have Elvis expressions")
        elvisExpressions.forEach { assertNotNull(astModel.getParent(it), "Elvis expression should have a parent") }
    }

    @Test
    fun `safe navigation tracked correctly`() {
        val uri = URI.create("file:///safe-nav-test.groovy")
        val code = """
            def result = obj?.method()?.field?.toString()
            def value = obj?.field ?: "default"
            def chain = a?.b?.c?.d
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")

        val astModel = result.astModel
        val safeNavs = astModel.getAllNodes()
            .filterIsInstance<PropertyExpression>()
            .filter { it.isSafe }
        assertTrue(safeNavs.isNotEmpty(), "Should have safe navigation expressions")
        safeNavs.forEach { assertNotNull(astModel.getParent(it), "Safe navigation should have a parent") }
    }

    @Test
    fun `spread operators tracked correctly`() {
        val uri = URI.create("file:///spread-test.groovy")
        val code = """
            def list1 = [1, 2, 3]
            def list2 = [0, *list1, 4]
            def args = [1, 2]
            println(*args)
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")

        val astModel = result.astModel
        val spreadExprs = astModel.getAllNodes().filterIsInstance<SpreadExpression>()
        assertTrue(spreadExprs.isNotEmpty(), "Should have spread expressions")
        spreadExprs.forEach { assertNotNull(astModel.getParent(it), "Spread expression should have a parent") }
    }

    @Test
    fun `combined groovy operators`() {
        val uri = URI.create("file:///operators-combined.groovy")
        val code = """
            class OperatorTest {
                def complexMethod() {
                    def list1 = [1, 2, 3]
                    def list2 = [0, *list1, 4]

                    def map1 = [a: 1]

                    def range = 0..10
                    def name = input ?: "default"
                    def safe = obj?.method()?.field

                    (0..<5).each { i ->
                        def val = i ?: 0
                        println(*list2)
                    }

                    return [range: range, map: map1, name: name]
                }
            }
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")

        val allNodes = result.astModel.getAllNodes()
        assertTrue(allNodes.any { it is RangeExpression }, "Should have RangeExpression")
        assertTrue(allNodes.any { it is ElvisOperatorExpression }, "Should have ElvisOperatorExpression")
        assertTrue(allNodes.any { it is ListExpression }, "Should have ListExpression")
        assertTrue(allNodes.any { it is MapExpression }, "Should have MapExpression")
    }
}
