package com.github.albertocavalcante.groovyparser.ast.query

import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AstQueryEngineTest {

    @Test
    fun `finds matches by type`() {
        val tree = sampleTree()
        val tracker = tree.tracker
        val root = tree.root
        val childA = tree.childA
        val childB = tree.childB
        val engine = AstQueryEngine(tracker::getChildren)
        val query = AstQuery.parse("(ConstantExpression)")

        val matches = engine.find(root, query)

        assertEquals(2, matches.size)
        assertTrue(matches.any { it.node === childA })
        assertTrue(matches.any { it.node === childB })
    }

    @Test
    fun `captures matching child`() {
        val tree = sampleTree()
        val tracker = tree.tracker
        val root = tree.root
        val childA = tree.childA
        val childB = tree.childB
        val engine = AstQueryEngine(tracker::getChildren)
        val query = AstQuery.parse("(BlockStatement (ConstantExpression @value))")

        val matches = engine.find(root, query)

        assertEquals(1, matches.size)
        val capture = matches.single().captures["value"]
        assertNotNull(capture)
        assertTrue(capture === childA || capture === childB)
    }

    @Test
    fun `captures distinct children for repeated patterns`() {
        val tree = sampleTree()
        val tracker = tree.tracker
        val root = tree.root
        val childA = tree.childA
        val childB = tree.childB
        val engine = AstQueryEngine(tracker::getChildren)
        val query = AstQuery.parse("(BlockStatement (ConstantExpression @a) (ConstantExpression @b))")

        val matches = engine.find(root, query)

        assertEquals(1, matches.size)
        val captures = matches.single().captures
        assertTrue(captures["a"] === childA || captures["a"] === childB)
        assertTrue(captures["b"] === childA || captures["b"] === childB)
        assertTrue(captures["a"] !== captures["b"])
    }

    private fun sampleTree(): SampleTree {
        val tracker = NodeRelationshipTracker()
        val root = BlockStatement()
        val childA = ConstantExpression("a")
        val childB = ConstantExpression("b")

        tracker.pushNode(root, null)
        tracker.pushNode(childA, null)
        tracker.popNode()
        tracker.pushNode(childB, null)
        tracker.popNode()
        tracker.popNode()

        return SampleTree(tracker, root, childA, childB)
    }

    private data class SampleTree(
        val tracker: NodeRelationshipTracker,
        val root: BlockStatement,
        val childA: ConstantExpression,
        val childB: ConstantExpression,
    )
}
