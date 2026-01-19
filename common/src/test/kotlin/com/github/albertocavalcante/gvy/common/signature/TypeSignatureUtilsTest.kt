package com.github.albertocavalcante.gvy.common.signature

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("TypeSignatureUtils")
class TypeSignatureUtilsTest {

    @Nested
    @DisplayName("extractParameterCount")
    inner class ExtractParameterCountTests {

        @Test
        fun `handles empty parameters`() {
            assertEquals(0, extractParameterCount("com/example/MyClass#myMethod()."))
        }

        @Test
        fun `handles single simple parameter`() {
            assertEquals(1, extractParameterCount("com/example/MyClass#myMethod(String)."))
        }

        @Test
        fun `handles multiple simple parameters`() {
            assertEquals(2, extractParameterCount("com/example/MyClass#myMethod(String,int)."))
            assertEquals(3, extractParameterCount("com/example/MyClass#myMethod(String,int,boolean)."))
        }

        @Test
        fun `handles Map with generic types`() {
            assertEquals(1, extractParameterCount("com/example/MyClass#myMethod(Map<String,String>)."))
        }

        @Test
        fun `handles Map with generics plus int`() {
            assertEquals(2, extractParameterCount("com/example/MyClass#myMethod(Map<String,String>,int)."))
        }

        @Test
        fun `handles nested generics`() {
            assertEquals(1, extractParameterCount("com/example/MyClass#myMethod(List<Map<String,Integer>>)."))
        }

        @Test
        fun `handles complex generic combinations`() {
            assertEquals(
                3,
                extractParameterCount("com/example/MyClass#myMethod(Map<String,List<Integer>>,String,int)."),
            )
        }

        @Test
        fun `differentiates overloaded methods by arity`() {
            assertEquals(0, extractParameterCount("com/example/MyClass#myMethod()."))
            assertEquals(1, extractParameterCount("com/example/MyClass#myMethod(String)."))
            assertEquals(2, extractParameterCount("com/example/MyClass#myMethod(String,int)."))
        }

        @Test
        fun `handles deeply nested generics`() {
            assertEquals(
                1,
                extractParameterCount("com/example/MyClass#myMethod(Map<String,Map<String,List<Integer>>>)."),
            )
        }

        @Test
        fun `handles varargs parameter`() {
            assertEquals(1, extractParameterCount("com/example/MyClass#myMethod(String...)."))
        }

        @Test
        fun `handles mixed varargs and generics`() {
            assertEquals(2, extractParameterCount("com/example/MyClass#myMethod(Map<String,String>,String...)."))
        }

        @Test
        fun `handles malformed signature gracefully`() {
            assertEquals(0, extractParameterCount("com/example/MyClass#myMethod"))
            assertEquals(0, extractParameterCount("com/example/MyClass#myMethod("))
            assertEquals(0, extractParameterCount("com/example/MyClass#myMethod)"))
            assertEquals(0, extractParameterCount("invalid"))
        }

        @Test
        fun `handles extremely long generic chain`() {
            assertEquals(
                1,
                extractParameterCount(
                    "com/example/MyClass#myMethod(Map<String,Map<String,Map<String,Integer>>>).",
                ),
            )
        }

        @Test
        fun `handles multiple consecutive commas in different contexts`() {
            // Commas inside generics should not be counted as parameter separators
            assertEquals(
                3,
                extractParameterCount("com/example/MyClass#myMethod(Map<String,String>,List<Integer,String>,int)."),
            )
        }
    }

    @Nested
    @DisplayName("parseSignatureParameters")
    inner class ParseSignatureParametersTests {

        @Test
        fun `handles null signature`() {
            assertEquals(emptyList(), parseSignatureParameters(null))
        }

        @Test
        fun `handles empty parameters`() {
            assertEquals(emptyList(), parseSignatureParameters("com/example/MyClass#myMethod()."))
        }

        @Test
        fun `handles single simple parameter`() {
            assertEquals(listOf("String"), parseSignatureParameters("com/example/MyClass#myMethod(String)."))
        }

        @Test
        fun `handles multiple simple parameters`() {
            assertEquals(
                listOf("String", "int"),
                parseSignatureParameters("com/example/MyClass#myMethod(String,int)."),
            )
        }

        @Test
        fun `simplifies fully qualified names`() {
            assertEquals(
                listOf("String"),
                parseSignatureParameters("com/example/MyClass#myMethod(java.lang.String)."),
            )
            assertEquals(
                listOf("String"),
                parseSignatureParameters("com/example/MyClass#myMethod(java/lang/String)."),
            )
        }

        @Test
        fun `handles Map with generic types`() {
            assertEquals(
                listOf("Map<String,String>"),
                parseSignatureParameters("com/example/MyClass#myMethod(Map<String,String>)."),
            )
        }

        @Test
        fun `handles nested generics`() {
            assertEquals(
                listOf("List<Map<String,Integer>>"),
                parseSignatureParameters("com/example/MyClass#myMethod(List<Map<String,Integer>>)."),
            )
        }

        @Test
        fun `simplifies FQN in main type but preserves generics`() {
            assertEquals(
                listOf("Map<String,String>", "int"),
                parseSignatureParameters("com/example/MyClass#myMethod(java/util/Map<String,String>,int)."),
            )
        }

        @Test
        fun `handles complex generic combinations`() {
            assertEquals(
                listOf("Map<String,List<Integer>>", "String", "int"),
                parseSignatureParameters("com/example/MyClass#myMethod(Map<String,List<Integer>>,String,int)."),
            )
        }

        @Test
        fun `handles varargs parameter`() {
            // Note: Varargs "..." is preserved as part of the type name
            assertEquals(
                listOf("String..."),
                parseSignatureParameters("com/example/MyClass#myMethod(String...)."),
            )
        }

        @Test
        fun `handles malformed signature gracefully`() {
            assertEquals(emptyList(), parseSignatureParameters("com/example/MyClass#myMethod"))
            assertEquals(emptyList(), parseSignatureParameters("com/example/MyClass#myMethod("))
            assertEquals(emptyList(), parseSignatureParameters("com/example/MyClass#myMethod)"))
            assertEquals(emptyList(), parseSignatureParameters("invalid"))
        }

        @Test
        fun `handles whitespace in parameters`() {
            assertEquals(
                listOf("String", "int"),
                parseSignatureParameters("com/example/MyClass#myMethod( String , int )."),
            )
        }

        @Test
        fun `preserves generic type parameters within angle brackets`() {
            assertEquals(
                listOf("Map<String,String>"),
                parseSignatureParameters("com/example/MyClass#myMethod(Map<String,String>)."),
            )
        }
    }
}
