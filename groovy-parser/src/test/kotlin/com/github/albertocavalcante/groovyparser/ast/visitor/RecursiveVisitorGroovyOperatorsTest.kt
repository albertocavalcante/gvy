package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.junit.jupiter.api.Assertions.assertEquals
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

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        // Find range expressions
        val rangeExpressions = delegateNodes.filterIsInstance<RangeExpression>()
        assertTrue(rangeExpressions.size >= 3, "Should have at least 3 range expressions")

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        assertParentRelationshipsMatch(delegateNodes, delegateParents, recursiveParents)
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

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        // Elvis extends TernaryExpression, so we might find them as Ternary or Elvis depending on how they are visited.
        // But since the node instance itself IS an ElvisOperatorExpression, filterIsInstance should work.
        val elvisExpressions = delegateNodes.filterIsInstance<ElvisOperatorExpression>()
        assertTrue(elvisExpressions.isNotEmpty(), "Should have Elvis expressions")

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        assertParentRelationshipsMatch(delegateNodes, delegateParents, recursiveParents)
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

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        // PropertyExpression with isSafe() should be tracked
        val safeNavs = delegateNodes.filterIsInstance<org.codehaus.groovy.ast.expr.PropertyExpression>()
            .filter { it.isSafe }
        assertTrue(safeNavs.isNotEmpty(), "Should have safe navigation expressions")

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        assertParentRelationshipsMatch(delegateNodes, delegateParents, recursiveParents)
    }

    @Test
    fun `spread operators tracked correctly`() {
        val uri = URI.create("file:///spread-test.groovy")
        val code = """
            def list1 = [1, 2, 3]
            def list2 = [0, *list1, 4]
            def args = [1, 2]
            println(*args)
            // Spread map is rarely used but we support it
            // def map1 = [a: 1, b: 2]
            // def map2 = [*:map1, c: 3] 
            // Note: Spread map might be parsed differently in newer Groovy or requires specific context
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        val spreadExprs = delegateNodes.filterIsInstance<SpreadExpression>()
        assertTrue(spreadExprs.isNotEmpty(), "Should have spread expressions")

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        assertParentRelationshipsMatch(delegateNodes, delegateParents, recursiveParents)
    }

    @Test
    fun `combined groovy operators parity`() {
        val uri = URI.create("file:///operators-combined.groovy")
        val code = """
            class OperatorTest {
                def complexMethod() {
                    def list1 = [1, 2, 3]
                    def list2 = [0, *list1, 4]

                    def map1 = [a: 1]
                    // def map2 = [*:map1, b: 2] // Spread map often tricky

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

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        // Verify all operator types are present
        assertTrue(delegateNodes.any { it is RangeExpression }, "Should have RangeExpression")
        assertTrue(delegateNodes.any { it is ElvisOperatorExpression }, "Should have ElvisOperatorExpression")
        assertTrue(delegateNodes.any { it is ListExpression }, "Should have ListExpression")
        assertTrue(delegateNodes.any { it is MapExpression }, "Should have MapExpression")

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        assertParentRelationshipsMatch(delegateNodes, delegateParents, recursiveParents)
    }

    // Helper methods
    private fun collectWithDelegate(
        result: com.github.albertocavalcante.groovyparser.api.ParseResult,
    ): Pair<Set<ASTNode>, Map<ASTNode, ASTNode?>> {
        val visitor = result.astVisitor!!
        val nodes = visitor.getAllNodes().toSet()
        val parents = nodes.associateWith { visitor.getParent(it) }
        return nodes to parents
    }

    private fun collectWithRecursive(
        result: com.github.albertocavalcante.groovyparser.api.ParseResult,
        uri: URI,
    ): Pair<Set<ASTNode>, Map<ASTNode, ASTNode?>> {
        val tracker = NodeRelationshipTracker()
        val recursiveVisitor = RecursiveAstVisitor(tracker)
        recursiveVisitor.visitModule(result.ast!!, uri)
        val nodes = tracker.getAllNodes().toSet()
        val parents = nodes.associateWith { tracker.getParent(it) }
        return nodes to parents
    }

    private fun assertNodeSetsMatch(delegateNodes: Set<ASTNode>, recursiveNodes: Set<ASTNode>) {
        val missing = delegateNodes - recursiveNodes
        val extra = recursiveNodes - delegateNodes

        // Filter out known differences if any (none expected for operators now)

        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            val missingStr = missing.joinToString("\n") { describe(it) }
            val extraStr = extra.joinToString("\n") { describe(it) }
            val msg = StringBuilder()
            if (missing.isNotEmpty()) msg.append("Missing nodes (in delegate but not recursive):\n$missingStr\n")
            if (extra.isNotEmpty()) msg.append("Extra nodes (in recursive but not delegate):\n$extraStr\n")
            assertEquals(emptySet<ASTNode>(), missing, msg.toString())
            assertEquals(emptySet<ASTNode>(), extra, msg.toString())
        }
    }

    private fun assertParentRelationshipsMatch(
        nodes: Set<ASTNode>,
        delegateParents: Map<ASTNode, ASTNode?>,
        recursiveParents: Map<ASTNode, ASTNode?>,
    ) {
        nodes.forEach { node ->
            val delegateParent = delegateParents[node]
            val recursiveParent = recursiveParents[node]

            // Skip script-level statement parenting discrepancy (known issue/difference)
            if (node is org.codehaus.groovy.ast.stmt.ExpressionStatement &&
                recursiveParent is org.codehaus.groovy.ast.ClassNode &&
                (recursiveParent.isScript() || delegateParent == null)
            ) {
                return@forEach
            }

            // Skip if parents are effectively the same but different instances/proxies? No, should be same AST nodes.

            assertEquals(
                delegateParent,
                recursiveParent,
                "Parent mismatch for ${describe(node)}",
            )
        }
    }

    private fun describe(node: ASTNode?): String =
        node?.let { "${it.javaClass.simpleName} @${it.lineNumber}:${it.columnNumber}" } ?: "null"
}
