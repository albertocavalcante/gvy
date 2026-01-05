package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeRelationshipTrackerTest {

    @Test
    fun `returns empty children for leaf nodes`() {
        val tracker = NodeRelationshipTracker()
        val node = ConstantExpression("value")

        tracker.pushNode(node, null)
        tracker.popNode()

        assertTrue(tracker.getChildren(node).isEmpty())
    }

    @Test
    fun `tracks direct children for same parent`() {
        val tracker = NodeRelationshipTracker()
        val parent = BlockStatement()
        val childA = ConstantExpression("a")
        val childB = ConstantExpression("b")

        tracker.pushNode(parent, null)
        tracker.pushNode(childA, null)
        tracker.popNode()
        tracker.pushNode(childB, null)
        tracker.popNode()
        tracker.popNode()

        val children = tracker.getChildren(parent)
        assertEquals(2, children.size)
        assertTrue(children[0] === childA)
        assertTrue(children[1] === childB)
    }

    @Test
    fun `tracks nested children without flattening`() {
        val tracker = NodeRelationshipTracker()
        val parent = BlockStatement()
        val child = ConstantExpression("child")
        val grandchild = ConstantExpression("grandchild")

        tracker.pushNode(parent, null)
        tracker.pushNode(child, null)
        tracker.pushNode(grandchild, null)
        tracker.popNode()
        tracker.popNode()
        tracker.popNode()

        val parentChildren = tracker.getChildren(parent)
        val childChildren = tracker.getChildren(child)

        assertEquals(1, parentChildren.size)
        assertTrue(parentChildren.contains(child))
        assertTrue(grandchild !in parentChildren)

        assertEquals(1, childChildren.size)
        assertTrue(childChildren.contains(grandchild))
    }
}
