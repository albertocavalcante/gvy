package com.github.albertocavalcante.groovyparser.utils

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PositionUtilsTest {

    @Test
    fun `isBefore returns true when position is before another`() {
        val pos1 = Position(1, 5)
        val pos2 = Position(1, 10)
        val pos3 = Position(2, 1)

        assertTrue(pos1.isBefore(pos2))
        assertTrue(pos1.isBefore(pos3))
        assertTrue(pos2.isBefore(pos3))
        assertFalse(pos2.isBefore(pos1))
    }

    @Test
    fun `isAfter returns true when position is after another`() {
        val pos1 = Position(1, 5)
        val pos2 = Position(1, 10)

        assertTrue(pos2.isAfter(pos1))
        assertFalse(pos1.isAfter(pos2))
    }

    @Test
    fun `rangeContains returns true when outer contains inner`() {
        val outer = Range(Position(1, 1), Position(10, 10))
        val inner = Range(Position(2, 1), Position(5, 5))
        val outside = Range(Position(11, 1), Position(12, 1))

        assertTrue(PositionUtils.rangeContains(outer, inner))
        assertFalse(PositionUtils.rangeContains(outer, outside))
        assertFalse(PositionUtils.rangeContains(inner, outer))
    }

    @Test
    fun `rangesOverlap detects overlapping ranges`() {
        val range1 = Range(Position(1, 1), Position(5, 10))
        val range2 = Range(Position(3, 1), Position(7, 10))
        val range3 = Range(Position(10, 1), Position(15, 10))

        assertTrue(PositionUtils.rangesOverlap(range1, range2))
        assertTrue(PositionUtils.rangesOverlap(range2, range1))
        assertFalse(PositionUtils.rangesOverlap(range1, range3))
    }

    @Test
    fun `isInRange checks if position is within range`() {
        val range = Range(Position(1, 1), Position(5, 10))

        assertTrue(PositionUtils.isInRange(Position(3, 5), range))
        assertTrue(PositionUtils.isInRange(Position(1, 1), range)) // start boundary
        assertTrue(PositionUtils.isInRange(Position(5, 10), range)) // end boundary
        assertFalse(PositionUtils.isInRange(Position(6, 1), range))
    }

    @Test
    fun `comparePositions compares positions correctly`() {
        val pos1 = Position(1, 5)
        val pos2 = Position(1, 10)
        val pos3 = Position(2, 1)

        assertTrue(PositionUtils.comparePositions(pos1, pos2) < 0)
        assertTrue(PositionUtils.comparePositions(pos2, pos1) > 0)
        assertTrue(PositionUtils.comparePositions(pos1, pos3) < 0)
        assertEquals(0, PositionUtils.comparePositions(pos1, Position(1, 5)))
    }

    @Test
    fun `sortByBeginPosition sorts nodes correctly`() {
        val code = """
            class First {}
            class Second {}
            class Third {}
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val types = result.result.get().types
        val sorted = PositionUtils.sortByBeginPosition(types)

        assertEquals("First", sorted[0].name)
        assertEquals("Second", sorted[1].name)
        assertEquals("Third", sorted[2].name)
    }

    @Test
    fun `nodeContains checks containment correctly`() {
        val code = """
            class Outer {
                def innerMethod() {}
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods.find { it.name == "innerMethod" }
        assertNotNull(method)

        assertTrue(PositionUtils.nodeContains(classDecl, method))
        assertFalse(PositionUtils.nodeContains(method, classDecl))
    }

    @Test
    fun `encompassingRange returns range covering all nodes`() {
        val code = """
            class A {}
            class B {}
            class C {}
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val types = result.result.get().types
        val range = PositionUtils.encompassingRange(types)

        assertNotNull(range)
        assertEquals(1, range.begin.line)
        assertEquals(3, range.end.line)
    }
}
