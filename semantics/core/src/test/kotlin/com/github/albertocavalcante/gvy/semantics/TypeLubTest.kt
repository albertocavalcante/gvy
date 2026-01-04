package com.github.albertocavalcante.gvy.semantics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TypeLubTest {

    @Test
    fun `lub of single type returns that type`() {
        val result = TypeLub.lub(listOf(TypeConstants.INT))
        assertEquals(TypeConstants.INT, result)
    }

    @Test
    fun `lub of same types returns that type`() {
        val result = TypeLub.lub(listOf(TypeConstants.INT, TypeConstants.INT))
        assertEquals(TypeConstants.INT, result)
    }

    @Test
    fun `lub of int and long is long`() {
        val result = TypeLub.lub(listOf(TypeConstants.INT, TypeConstants.LONG))
        assertEquals(TypeConstants.LONG, result)
    }

    @Test
    fun `lub of int and double is double`() {
        val result = TypeLub.lub(listOf(TypeConstants.INT, TypeConstants.DOUBLE))
        assertEquals(TypeConstants.DOUBLE, result)
    }

    @Test
    fun `lub of float and double is double`() {
        val result = TypeLub.lub(listOf(TypeConstants.FLOAT, TypeConstants.DOUBLE))
        assertEquals(TypeConstants.DOUBLE, result)
    }

    @Test
    fun `lub of byte and short is int`() {
        val result = TypeLub.lub(listOf(TypeConstants.BYTE, TypeConstants.SHORT))
        assertEquals(TypeConstants.INT, result)
    }

    @Test
    fun `lub with null type returns the other type`() {
        val result = TypeLub.lub(listOf(TypeConstants.INT, SemanticType.Null))
        assertEquals(TypeConstants.INT, result)
    }

    @Test
    fun `lub of only null types returns null`() {
        val result = TypeLub.lub(listOf(SemanticType.Null, SemanticType.Null))
        assertEquals(SemanticType.Null, result)
    }

    // --- Reference Types ---

    @Test
    fun `lub of reference types finds common ancestor`() {
        val stringType = TypeConstants.STRING
        // Integer is final, so we can't easily mock it if we needed to, but we have hardcoded support
        val integerType = SemanticType.Known("java.lang.Integer")

        val result = TypeLub.lub(listOf(stringType, integerType))

        // String -> CharSequence, Comparable, Serializable, Object
        // Integer -> Number (-> Serializable), Comparable, Object
        // Intersection: Comparable, Serializable, Object.
        // Priority: Comparable(5) < Serializable(10) < Object(100).
        // Result: Comparable.

        assertEquals("java.lang.Comparable", (result as SemanticType.Known).fqn)
    }

    @Test
    fun `lub of ArrayList and LinkedList is List`() {
        val arrayList = SemanticType.Known("java.util.ArrayList")
        val linkedList = SemanticType.Known("java.util.LinkedList")

        val result = TypeLub.lub(listOf(arrayList, linkedList))

        // ArrayList -> List, RandomAccess, Cloneable, Serializable, Collection, Iterable, Object
        // LinkedList -> List, Deque, Cloneable, Serializable, Collection, Iterable, Object
        // Intersection: List, Cloneable, Serializable, Collection, Iterable, Object.
        // Priority: List (1), Collection (2), Iterable (3), Serializable (10).
        // Result: List.

        assertEquals("java.util.List", (result as SemanticType.Known).fqn)
    }

    @Test
    fun `lub of empty list throws exception`() {
        assertThrows<IllegalArgumentException> {
            TypeLub.lub(emptyList())
        }
    }

    @Test
    fun `lub of booleans is boolean`() {
        val result = TypeLub.lub(listOf(TypeConstants.BOOLEAN, TypeConstants.BOOLEAN))
        assertEquals(TypeConstants.BOOLEAN, result)
    }

    @Test
    fun `lub of boolean and numeric is object`() {
        // Mixed boolean and numeric cannot participate in numeric promotion.
        // Fallback behavior is the common reference ancestor, which is Object.
        val result = TypeLub.lub(listOf(TypeConstants.BOOLEAN, TypeConstants.INT))

        // Should fall back to Object
        assertEquals(TypeConstants.OBJECT, result)
    }

    @Test
    fun `lub of void and void is void`() {
        val result = TypeLub.lub(listOf(TypeConstants.VOID, TypeConstants.VOID))
        assertEquals(TypeConstants.VOID, result)
    }

    @Test
    fun `lub of void and int is object`() {
        val result = TypeLub.lub(listOf(TypeConstants.VOID, TypeConstants.INT))
        assertEquals(TypeConstants.OBJECT, result)
    }

    @Test
    fun `promoteNumeric throws when void is present`() {
        assertThrows<IllegalArgumentException> {
            TypeLub.promoteNumeric(
                listOf(TypeConstants.VOID, TypeConstants.INT),
            )
        }
    }

    @Test
    fun `lub of char and byte is int`() {
        val result = TypeLub.lub(listOf(TypeConstants.CHAR, TypeConstants.BYTE))
        assertEquals(TypeConstants.INT, result)
    }

    // --- Groovy Specific ---

    @Test
    fun `GString + String = String`() {
        val result = TypeLub.lub(listOf(TypeConstants.GSTRING, TypeConstants.STRING))
        assertEquals(TypeConstants.STRING, result)
    }

    @Test
    fun `Integer + BigDecimal = BigDecimal`() {
        val result = TypeLub.lub(listOf(TypeConstants.INT, TypeConstants.BIG_DECIMAL))
        assertEquals(TypeConstants.BIG_DECIMAL, result)
    }

    @Test
    fun `BigInteger + Double = BigDecimal`() {
        // BigInteger (rank 6) + Double (rank 9) ?
        // Groovy rules: BigInteger + Double promotes to BigDecimal to preserve precision.
        // Rankings: BigInteger(6), Double(9).
        // Max rank logic ensures we pick BigDecimal over Double when BigInteger is present to avoid precision loss.

        val result = TypeLub.lub(listOf(TypeConstants.BIG_INTEGER, TypeConstants.DOUBLE))
        // With my logic: MaxRank=9 (DOUBLE).
        // My logic: if >= 6 -> if (rank==6) BI else BD.
        // So returns BD.
        // This seems safer than Object.
        assertEquals(TypeConstants.BIG_DECIMAL, result)
    }

    @Test
    fun `lub of primitive int and wrapper Long is Long`() {
        val primitiveInt = SemanticType.Primitive(PrimitiveKind.INT)
        val wrapperLong = SemanticType.Known("java.lang.Long")

        val result = TypeLub.lub(listOf(primitiveInt, wrapperLong))

        assertEquals(wrapperLong, result, "LUB(int, Long) should be Long")
    }

    @Test
    fun `lub of primitive float and wrapper Double is Double`() {
        val primitiveFloat = SemanticType.Primitive(PrimitiveKind.FLOAT)
        val wrapperDouble = SemanticType.Known("java.lang.Double")

        val result = TypeLub.lub(listOf(primitiveFloat, wrapperDouble))

        assertEquals(wrapperDouble, result, "LUB(float, Double) should be Double")
    }

    @Test
    fun `lub of wrapper Integer and primitive long is long`() {
        val wrapperInt = SemanticType.Known("java.lang.Integer")
        val primitiveLong = SemanticType.Primitive(PrimitiveKind.LONG)

        val result = TypeLub.lub(listOf(wrapperInt, primitiveLong))

        assertEquals(TypeConstants.LONG, result, "LUB(Integer, long) should be long")
    }

    @Test
    fun `lub with Union type returns Object`() {
        // Union types don't have a simple common ancestor in the current implementation,
        // so LUB falls back to Object
        val union = SemanticType.Union(
            setOf(
                SemanticType.Known("java.lang.String"),
                SemanticType.Primitive(PrimitiveKind.INT),
            ),
        )
        val known = SemanticType.Known("java.lang.Integer")

        val result = TypeLub.lub(listOf(union, known))

        assertEquals(TypeConstants.OBJECT, result, "LUB involving Union should fall back to Object")
    }
}
