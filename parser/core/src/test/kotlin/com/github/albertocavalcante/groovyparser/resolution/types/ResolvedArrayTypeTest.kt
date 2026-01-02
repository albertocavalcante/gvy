package com.github.albertocavalcante.groovyparser.resolution.types

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolvedArrayTypeTest {

    @Test
    fun `describe returns component type with brackets`() {
        val intArray = ResolvedArrayType(ResolvedPrimitiveType.INT)
        assertEquals("int[]", intArray.describe())
    }

    @Test
    fun `describe handles nested arrays`() {
        val intArray = ResolvedArrayType(ResolvedPrimitiveType.INT)
        val int2DArray = ResolvedArrayType(intArray)
        assertEquals("int[][]", int2DArray.describe())
    }

    @Test
    fun `isArray returns true`() {
        val intArray = ResolvedArrayType(ResolvedPrimitiveType.INT)
        assertTrue(intArray.isArray())
    }

    @Test
    fun `componentType returns inner type`() {
        val intArray = ResolvedArrayType(ResolvedPrimitiveType.INT)
        assertEquals(ResolvedPrimitiveType.INT, intArray.componentType)
    }

    @Test
    fun `arrayLevel returns correct depth`() {
        val intArray = ResolvedArrayType(ResolvedPrimitiveType.INT)
        val int2DArray = ResolvedArrayType(intArray)
        val int3DArray = ResolvedArrayType(int2DArray)

        assertEquals(1, intArray.arrayLevel())
        assertEquals(2, int2DArray.arrayLevel())
        assertEquals(3, int3DArray.arrayLevel())
    }

    @Test
    fun `array is assignable from same component type array`() {
        val intArray1 = ResolvedArrayType(ResolvedPrimitiveType.INT)
        val intArray2 = ResolvedArrayType(ResolvedPrimitiveType.INT)
        assertTrue(intArray1.isAssignableBy(intArray2))
    }

    @Test
    fun `array is not assignable from different component type array`() {
        val intArray = ResolvedArrayType(ResolvedPrimitiveType.INT)
        val longArray = ResolvedArrayType(ResolvedPrimitiveType.LONG)
        assertFalse(intArray.isAssignableBy(longArray))
    }

    @Test
    fun `asArrayType returns self`() {
        val intArray = ResolvedArrayType(ResolvedPrimitiveType.INT)
        assertEquals(intArray, intArray.asArrayType())
    }
}
