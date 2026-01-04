package com.github.albertocavalcante.gvy.semantics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimitiveKindTest {
    @Test
    fun `isNumeric classification`() {
        assertTrue(PrimitiveKind.BYTE.isNumeric)
        assertTrue(PrimitiveKind.SHORT.isNumeric)
        assertTrue(PrimitiveKind.INT.isNumeric)
        assertTrue(PrimitiveKind.LONG.isNumeric)
        assertTrue(PrimitiveKind.FLOAT.isNumeric)
        assertTrue(PrimitiveKind.DOUBLE.isNumeric)
        assertTrue(PrimitiveKind.CHAR.isNumeric)

        assertFalse(PrimitiveKind.BOOLEAN.isNumeric)
    }

    @Test
    fun `isIntegral classification`() {
        assertTrue(PrimitiveKind.BYTE.isIntegral)
        assertTrue(PrimitiveKind.SHORT.isIntegral)
        assertTrue(PrimitiveKind.INT.isIntegral)
        assertTrue(PrimitiveKind.LONG.isIntegral)
        assertTrue(PrimitiveKind.CHAR.isIntegral) // Char is integral in Groovy/Java

        assertFalse(PrimitiveKind.FLOAT.isIntegral)
        assertFalse(PrimitiveKind.DOUBLE.isIntegral)
        assertFalse(PrimitiveKind.BOOLEAN.isIntegral)
    }

    @Test
    fun `isFloatingPoint classification`() {
        assertTrue(PrimitiveKind.FLOAT.isFloatingPoint)
        assertTrue(PrimitiveKind.DOUBLE.isFloatingPoint)

        assertFalse(PrimitiveKind.BYTE.isFloatingPoint)
        assertFalse(PrimitiveKind.INT.isFloatingPoint)
        assertFalse(PrimitiveKind.BOOLEAN.isFloatingPoint)
    }
}
