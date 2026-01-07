package com.github.albertocavalcante.gvy.semantics.openrewrite

import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.openrewrite.java.tree.JavaType
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for [LstTypeMapper] which maps OpenRewrite LST types to SemanticType.
 */
class LstTypeMapperTest {

    @Nested
    @DisplayName("Primitive Types")
    inner class PrimitiveTypes {

        @Test
        fun `maps int primitive to SemanticType Primitive INT`() {
            val javaType = JavaType.Primitive.Int

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.INT, result.kind)
        }

        @Test
        fun `maps boolean primitive to SemanticType Primitive BOOLEAN`() {
            val javaType = JavaType.Primitive.Boolean

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.BOOLEAN, result.kind)
        }

        @Test
        fun `maps double primitive to SemanticType Primitive DOUBLE`() {
            val javaType = JavaType.Primitive.Double

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.DOUBLE, result.kind)
        }

        @Test
        fun `maps long primitive to SemanticType Primitive LONG`() {
            val javaType = JavaType.Primitive.Long

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.LONG, result.kind)
        }

        @Test
        fun `maps float primitive to SemanticType Primitive FLOAT`() {
            val javaType = JavaType.Primitive.Float

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.FLOAT, result.kind)
        }

        @Test
        fun `maps byte primitive to SemanticType Primitive BYTE`() {
            val javaType = JavaType.Primitive.Byte

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.BYTE, result.kind)
        }

        @Test
        fun `maps short primitive to SemanticType Primitive SHORT`() {
            val javaType = JavaType.Primitive.Short

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.SHORT, result.kind)
        }

        @Test
        fun `maps char primitive to SemanticType Primitive CHAR`() {
            val javaType = JavaType.Primitive.Char

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.CHAR, result.kind)
        }

        @Test
        fun `maps void primitive to SemanticType Primitive VOID`() {
            val javaType = JavaType.Primitive.Void

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.VOID, result.kind)
        }

        @Test
        fun `maps None primitive to SemanticType Dynamic`() {
            val javaType = JavaType.Primitive.None

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Dynamic>(result)
        }

        @Test
        fun `maps Null primitive to SemanticType Null`() {
            val javaType = JavaType.Primitive.Null

            val result = LstTypeMapper.toSemanticType(javaType)

            assertEquals(SemanticType.Null, result)
        }
    }

    @Nested
    @DisplayName("Class Types")
    inner class ClassTypes {

        @Test
        fun `maps String class to SemanticType Known`() {
            val javaType = JavaType.ShallowClass.build("java.lang.String")

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Known>(result)
            assertEquals("java.lang.String", result.fqn)
        }

        @Test
        fun `maps custom class to SemanticType Known`() {
            val javaType = JavaType.ShallowClass.build("com.example.MyClass")

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Known>(result)
            assertEquals("com.example.MyClass", result.fqn)
        }

        @Test
        fun `maps List class to SemanticType Known`() {
            val javaType = JavaType.ShallowClass.build("java.util.List")

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Known>(result)
            assertEquals("java.util.List", result.fqn)
        }

        @Test
        fun `maps Object class to SemanticType Known`() {
            val javaType = JavaType.ShallowClass.build("java.lang.Object")

            val result = LstTypeMapper.toSemanticType(javaType)

            assertIs<SemanticType.Known>(result)
            assertEquals("java.lang.Object", result.fqn)
        }
    }

    @Nested
    @DisplayName("Array Types")
    inner class ArrayTypes {

        @Test
        fun `maps String array to SemanticType Array`() {
            val componentType = JavaType.ShallowClass.build("java.lang.String")
            val arrayType = JavaType.Array(null, componentType, null)

            val result = LstTypeMapper.toSemanticType(arrayType)

            assertIs<SemanticType.Array>(result)
            val innerType = result.componentType
            assertIs<SemanticType.Known>(innerType)
            assertEquals("java.lang.String", innerType.fqn)
        }

        @Test
        fun `maps int array to SemanticType Array with Primitive`() {
            val arrayType = JavaType.Array(null, JavaType.Primitive.Int, null)

            val result = LstTypeMapper.toSemanticType(arrayType)

            assertIs<SemanticType.Array>(result)
            val innerType = result.componentType
            assertIs<SemanticType.Primitive>(innerType)
            assertEquals(PrimitiveKind.INT, innerType.kind)
        }
    }

    @Nested
    @DisplayName("Parameterized Types")
    inner class ParameterizedTypes {

        @Test
        fun `maps List of String to SemanticType Known with type args`() {
            val stringType = JavaType.ShallowClass.build("java.lang.String")
            val listType = JavaType.Parameterized(
                null,
                JavaType.ShallowClass.build("java.util.List"),
                listOf(stringType),
            )

            val result = LstTypeMapper.toSemanticType(listType)

            assertIs<SemanticType.Known>(result)
            assertEquals("java.util.List", result.fqn)
            assertEquals(1, result.typeArgs.size)
            val typeArg = result.typeArgs[0]
            assertIs<SemanticType.Known>(typeArg)
            assertEquals("java.lang.String", typeArg.fqn)
        }

        @Test
        fun `maps Map of String to Integer to SemanticType Known with type args`() {
            val stringType = JavaType.ShallowClass.build("java.lang.String")
            val intType = JavaType.ShallowClass.build("java.lang.Integer")
            val mapType = JavaType.Parameterized(
                null,
                JavaType.ShallowClass.build("java.util.Map"),
                listOf(stringType, intType),
            )

            val result = LstTypeMapper.toSemanticType(mapType)

            assertIs<SemanticType.Known>(result)
            assertEquals("java.util.Map", result.fqn)
            assertEquals(2, result.typeArgs.size)
        }
    }

    @Nested
    @DisplayName("Null Handling")
    inner class NullHandling {

        @Test
        fun `returns null for null JavaType input`() {
            val result = LstTypeMapper.toSemanticType(null)

            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Unknown Types")
    inner class UnknownTypes {

        @Test
        fun `maps Unknown JavaType to SemanticType Unknown`() {
            val unknownType = JavaType.Unknown.getInstance()

            val result = LstTypeMapper.toSemanticType(unknownType)

            assertIs<SemanticType.Unknown>(result)
        }
    }
}
