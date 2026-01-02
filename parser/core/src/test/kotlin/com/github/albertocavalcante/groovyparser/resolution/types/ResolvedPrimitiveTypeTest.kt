package com.github.albertocavalcante.groovyparser.resolution.types

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolvedPrimitiveTypeTest {

    @Test
    fun `int is assignable from int`() {
        assertTrue(ResolvedPrimitiveType.INT.isAssignableBy(ResolvedPrimitiveType.INT))
    }

    @Test
    fun `int is assignable from byte`() {
        assertTrue(ResolvedPrimitiveType.INT.isAssignableBy(ResolvedPrimitiveType.BYTE))
    }

    @Test
    fun `int is assignable from short`() {
        assertTrue(ResolvedPrimitiveType.INT.isAssignableBy(ResolvedPrimitiveType.SHORT))
    }

    @Test
    fun `int is assignable from char`() {
        assertTrue(ResolvedPrimitiveType.INT.isAssignableBy(ResolvedPrimitiveType.CHAR))
    }

    @Test
    fun `byte is not assignable from int`() {
        assertFalse(ResolvedPrimitiveType.BYTE.isAssignableBy(ResolvedPrimitiveType.INT))
    }

    @Test
    fun `long is assignable from int`() {
        assertTrue(ResolvedPrimitiveType.LONG.isAssignableBy(ResolvedPrimitiveType.INT))
    }

    @Test
    fun `float is assignable from int`() {
        assertTrue(ResolvedPrimitiveType.FLOAT.isAssignableBy(ResolvedPrimitiveType.INT))
    }

    @Test
    fun `float is assignable from long`() {
        assertTrue(ResolvedPrimitiveType.FLOAT.isAssignableBy(ResolvedPrimitiveType.LONG))
    }

    @Test
    fun `double is assignable from float`() {
        assertTrue(ResolvedPrimitiveType.DOUBLE.isAssignableBy(ResolvedPrimitiveType.FLOAT))
    }

    @Test
    fun `double is assignable from int`() {
        assertTrue(ResolvedPrimitiveType.DOUBLE.isAssignableBy(ResolvedPrimitiveType.INT))
    }

    @Test
    fun `boolean is only assignable from boolean`() {
        assertTrue(ResolvedPrimitiveType.BOOLEAN.isAssignableBy(ResolvedPrimitiveType.BOOLEAN))
        assertFalse(ResolvedPrimitiveType.BOOLEAN.isAssignableBy(ResolvedPrimitiveType.INT))
    }

    @Test
    fun `describe returns lowercase name`() {
        assertEquals("int", ResolvedPrimitiveType.INT.describe())
        assertEquals("boolean", ResolvedPrimitiveType.BOOLEAN.describe())
        assertEquals("double", ResolvedPrimitiveType.DOUBLE.describe())
    }

    @Test
    fun `isPrimitive returns true`() {
        assertTrue(ResolvedPrimitiveType.INT.isPrimitive())
        assertTrue(ResolvedPrimitiveType.BOOLEAN.isPrimitive())
    }

    @Test
    fun `byName finds primitive types`() {
        assertEquals(ResolvedPrimitiveType.INT, ResolvedPrimitiveType.byName("int"))
        assertEquals(ResolvedPrimitiveType.INT, ResolvedPrimitiveType.byName("INT"))
        assertEquals(ResolvedPrimitiveType.BOOLEAN, ResolvedPrimitiveType.byName("boolean"))
        assertEquals(null, ResolvedPrimitiveType.byName("unknown"))
    }

    @Test
    fun `box returns correct boxed type name`() {
        assertEquals("java.lang.Integer", ResolvedPrimitiveType.INT.box())
        assertEquals("java.lang.Boolean", ResolvedPrimitiveType.BOOLEAN.box())
        assertEquals("java.lang.Double", ResolvedPrimitiveType.DOUBLE.box())
        assertEquals("java.lang.Character", ResolvedPrimitiveType.CHAR.box())
    }

    @Test
    fun `asPrimitive returns self`() {
        assertEquals(ResolvedPrimitiveType.INT, ResolvedPrimitiveType.INT.asPrimitive())
    }
}
