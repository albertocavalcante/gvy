// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AstNavigationTest {
    private fun node(id: String, range: RangeDto?, vararg children: AstNodeDto) =
        CoreAstNodeDto(id, "test", range, children.toList(), emptyMap())

    @Test
    fun `membership uses DTO identity even when values and ids are identical`() {
        val owned = node("same", RangeDto(1, 1, 1, 2))
        val navigation = AstNavigation(owned, "x")
        assertTrue(navigation.contains(owned))
        assertFalse(navigation.contains(owned.copy()))
        assertSame(owned, navigation.nodeAt(1, 1))
    }

    @Test
    fun `unavailable or invalid parent ranges do not hide valid child spans`() {
        val child = node("child", RangeDto(1, 2, 1, 3))
        for (range in listOf(null, RangeDto(2, 1, 1, 1), RangeDto(1, 1, 1, 99))) {
            val root = node("root", range, child)
            val navigation = AstNavigation(root, "abc")
            assertSame(child, navigation.nodeAt(1, 2))
            assertTrue(navigation.contains(root))
        }
    }

    @Test
    fun `equal sibling spans prefer preorder and points cannot win a hit`() {
        val point = node("point", RangeDto(1, 1, 1, 1))
        val first = node("first", RangeDto(1, 1, 1, 2))
        val second = node("second", first.range)
        val navigation = AstNavigation(node("root", null, point, first, second), "x")
        assertSame(first, navigation.nodeAt(1, 1))
        assertNull(navigation.nodeAt(1, 2))
        assertNull(navigation.nodeAt(0, 1))
        assertNull(navigation.nodeAt(1, Int.MAX_VALUE))
    }

    @Test
    fun `wide snapshot preserves the original chosen DTO and bounded line coordinates`() {
        val source = "x".repeat(10_000) + "\r\n😀y"
        val nodes = List(10_000) { index -> node("$index", RangeDto(1, index + 1, 1, index + 2)) }
        val unicode = node("unicode", RangeDto(2, 3, 2, 4))
        val navigation = AstNavigation(node("root", null, *(nodes + unicode).toTypedArray()), source)
        assertSame(nodes.last(), navigation.nodeAt(1, 10_000))
        assertNull(navigation.nodeAt(1, 10_001))
        assertSame(unicode, navigation.nodeAt(2, 3))
        assertNull(navigation.nodeAt(2, 4))
    }
}
