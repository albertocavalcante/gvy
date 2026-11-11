package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AstTreeProjectionTest {
    private fun node(id: String, vararg children: AstNodeDto): CoreAstNodeDto =
        CoreAstNodeDto(id, "Node", null, children.toList(), mapOf("name" to id))

    @Test
    fun `filtered paths retain original nodes and manual expansion without mutation`() {
        val target = node("needle")
        val method = node("method", target)
        val branch = node("branch", method, node("other"))
        val root = node("root", branch)
        val projection = AstTreeProjection(root)
        val expansion = mapOf("root" to false, "method" to false)
        val rows = projection.visibleRows(expansion, projection.matchingIds("NEEDLE"))
        assertEquals(listOf("root", "branch", "method", "needle"), rows.map { it.node.id })
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.depth })
        assertEquals(listOf(null, "root", "branch", "method"), rows.map { it.parentId })
        assertSame(target, rows.last().node)
        assertTrue(rows.dropLast(1).all { it.isExpanded })
        assertEquals(listOf("root"), projection.visibleRows(expansion, null).map { it.node.id })
        assertEquals(mapOf("root" to false, "method" to false), expansion)
        assertTrue(projection.visibleRows(expansion, projection.matchingIds("absent")).isEmpty())
    }

    @Test
    fun `selection paths reject copies and ids reused by a different parse`() {
        val target = node("leaf")
        val root = node("root", node("parent", target))
        val projection = AstTreeProjection(root)
        assertEquals(listOf("root", "parent", "leaf"), projection.pathTo(target))
        assertTrue(projection.pathTo(target.copy()).isEmpty())
        assertTrue(AstTreeProjection(node("root", node("leaf"))).pathTo(target).isEmpty())
    }

    @Test
    fun `default expansion is two levels and explicit collapsed subtrees stay closed`() {
        val root = node("root", node("branch", node("method", node("leaf"))))
        val projection = AstTreeProjection(root)
        assertEquals(listOf("root", "branch", "method"), projection.visibleRows(emptyMap(), null).map { it.node.id })
        assertEquals(
            listOf("root", "branch"),
            projection.visibleRows(mapOf("branch" to false), null).map {
                it.node.id
            },
        )
        assertEquals(4, projection.visibleRows(mapOf("method" to true), null).size)
    }

    @Test
    fun `deep and wide trees are indexed without recursive traversal`() {
        val target = node("needle")
        var root: AstNodeDto = target
        repeat(3_000) { root = node("ancestor-$it", root) }
        val deep = AstTreeProjection(root)
        assertEquals(3_001, deep.pathTo(target).size)
        val rows = deep.visibleRows(emptyMap(), deep.matchingIds("needle"))
        assertEquals(3_001, rows.size)
        assertSame(target, rows.last().node)
        val wide = AstTreeProjection(node("root", *(0 until 5_000).map { node("child-$it") }.toTypedArray()))
        assertEquals(5_001, wide.visibleRows(emptyMap(), null).size)
    }
}
