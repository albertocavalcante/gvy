package com.github.albertocavalcante.groovyparser.resolution.typeinference

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NumericLubLogicTest {

    @Test
    fun `binary numeric promotion for double`() {
        assertEquals(
            ResolvedPrimitiveType.DOUBLE,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.DOUBLE, ResolvedPrimitiveType.INT),
        )
        assertEquals(
            ResolvedPrimitiveType.DOUBLE,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.FLOAT, ResolvedPrimitiveType.DOUBLE),
        )
    }

    @Test
    fun `binary numeric promotion for float`() {
        assertEquals(
            ResolvedPrimitiveType.FLOAT,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.FLOAT, ResolvedPrimitiveType.INT),
        )
        assertEquals(
            ResolvedPrimitiveType.FLOAT,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.LONG, ResolvedPrimitiveType.FLOAT),
        )
    }

    @Test
    fun `binary numeric promotion for long`() {
        assertEquals(
            ResolvedPrimitiveType.LONG,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.LONG, ResolvedPrimitiveType.INT),
        )
        assertEquals(
            ResolvedPrimitiveType.LONG,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.INT, ResolvedPrimitiveType.LONG),
        )
    }

    @Test
    fun `binary numeric promotion for integral types results in int`() {
        assertEquals(
            ResolvedPrimitiveType.INT,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.INT, ResolvedPrimitiveType.INT),
        )
        assertEquals(
            ResolvedPrimitiveType.INT,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.BYTE, ResolvedPrimitiveType.BYTE),
        )
        assertEquals(
            ResolvedPrimitiveType.INT,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.SHORT, ResolvedPrimitiveType.SHORT),
        )
        assertEquals(
            ResolvedPrimitiveType.INT,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.CHAR, ResolvedPrimitiveType.CHAR),
        )
        assertEquals(
            ResolvedPrimitiveType.INT,
            NumericLubLogic.binaryNumericPromotion(ResolvedPrimitiveType.BYTE, ResolvedPrimitiveType.SHORT),
        )
    }
}
