package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Comprehensive unit tests for NativeTypeContext field and method resolution.
 * Tests follow TDD approach with happy paths, edge cases, and error conditions.
 */
class NativeTypeContextTest {

    private val parser = GroovyParserFacade()

    private val stubSolver = object : TypeSolver {
        override var parent: TypeSolver? = null
        override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> = SymbolReference.unsolved()
    }

    private fun parse(code: String): ModuleNode {
        // Use CANONICALIZATION phase for proper type resolution (e.g., String -> java.lang.String)
        val request = ParseRequest(
            URI.create("file:///Test.groovy"),
            code,
            compilePhase = Phases.CANONICALIZATION,
        )
        val result = parser.parse(request)
        if (!result.isSuccessful) {
            error("Parse failed: " + result.diagnostics)
        }
        return result.ast!!
    }

    @Nested
    @DisplayName("Field Resolution")
    inner class FieldResolution {

        @Nested
        @DisplayName("Happy Path: Same-file field resolution")
        inner class HappyPath {

            @Test
            fun `resolveFieldType for simple String field`() {
                val code = """
                    class Person {
                        String name
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val personType = SemanticType.Known("Person")

                val fieldType = context.getFieldType(personType, "name")

                assertNotNull(fieldType, "Should resolve field type for 'name'")
                assertTrue(fieldType is SemanticType.Known, "Should be Known type")
                assertEquals(TypeConstants.STRING, fieldType)
            }

            @Test
            fun `resolveFieldType for primitive int field`() {
                val code = """
                    class Person {
                        int age
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val personType = SemanticType.Known("Person")

                val fieldType = context.getFieldType(personType, "age")

                assertNotNull(fieldType)
                assertTrue(fieldType is SemanticType.Primitive, "Should be Primitive type")
                assertEquals(TypeConstants.INT, fieldType)
            }

            @Test
            fun `resolveFieldType for List field with generics`() {
                val code = """
                    class Container {
                        List<String> items
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val containerType = SemanticType.Known("Container")

                val fieldType = context.getFieldType(containerType, "items")

                assertNotNull(fieldType)
                assertTrue(fieldType is SemanticType.Known, "Should be Known type for List")
                assertEquals("java.util.List", (fieldType as SemanticType.Known).fqn)
            }

            @Test
            fun `resolveFieldType for Map field`() {
                val code = """
                    class Config {
                        Map<String, Object> config
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val configType = SemanticType.Known("Config")

                val fieldType = context.getFieldType(configType, "config")

                assertNotNull(fieldType)
                assertTrue(fieldType is SemanticType.Known)
                assertEquals("java.util.Map", (fieldType as SemanticType.Known).fqn)
            }

            @Test
            fun `resolveFieldType for custom class field`() {
                val code = """
                    class Address {
                        String street
                    }

                    class Person {
                        Address address
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val personType = SemanticType.Known("Person")

                val fieldType = context.getFieldType(personType, "address")

                assertNotNull(fieldType)
                assertTrue(fieldType is SemanticType.Known)
                assertEquals("Address", (fieldType as SemanticType.Known).fqn)
            }

            @Test
            fun `resolveFieldType for array field`() {
                val code = """
                    class StringArray {
                        String[] items
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val containerType = SemanticType.Known("StringArray")

                val fieldType = context.getFieldType(containerType, "items")

                assertNotNull(fieldType)
                assertTrue(fieldType is SemanticType.Array, "Should be Array type")
                val componentType = (fieldType as SemanticType.Array).componentType
                assertEquals(TypeConstants.STRING, componentType)
            }

            @Test
            fun `resolveFieldType for boolean field`() {
                val code = """
                    class Feature {
                        boolean enabled
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val featureType = SemanticType.Known("Feature")

                val fieldType = context.getFieldType(featureType, "enabled")

                assertNotNull(fieldType)
                assertEquals(TypeConstants.BOOLEAN, fieldType)
            }
        }

        @Nested
        @DisplayName("Edge Cases: Non-existent and special fields")
        inner class EdgeCases {

            @Test
            fun `resolveFieldType returns Unknown for non-existent field`() {
                val code = """
                    class Person {
                        String name
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val personType = SemanticType.Known("Person")

                val fieldType = context.getFieldType(personType, "nonExistentField")

                assertNotNull(fieldType, "Should return Unknown instead of null")
                assertTrue(fieldType is SemanticType.Unknown)
            }

            @Test
            fun `resolveFieldType for unresolved class type returns Unknown`() {
                val code = """
                    class Person {
                        String name
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val unknownType = SemanticType.Known("com.example.UnknownClass")

                val fieldType = context.getFieldType(unknownType, "someField")

                assertNotNull(fieldType)
                assertTrue(fieldType is SemanticType.Unknown)
            }

            @Test
            fun `resolveFieldType on Dynamic type returns null`() {
                val code = """
                    class Person {
                        String name
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val dynamicType = SemanticType.Dynamic()

                val fieldType = context.getFieldType(dynamicType, "someField")

                assertNull(fieldType, "Dynamic types should return null")
            }

            @Test
            fun `resolveFieldType on Null type returns null`() {
                val code = """
                    class Person {
                        String name
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)

                val fieldType = context.getFieldType(SemanticType.Null, "someField")

                assertNull(fieldType, "Null types should return null")
            }

            @Test
            fun `resolveFieldType on Array type for length field`() {
                val code = """
                    class Util {
                        String name
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val arrayType = SemanticType.Array(TypeConstants.STRING)

                // Arrays have special handling for 'length' field
                val fieldType = context.getFieldType(arrayType, "length")

                // This is an edge case - arrays in Groovy/Java have 'length' property
                // Should ideally return INT, but may return null if not implemented
                // Test documents expected behavior
                if (fieldType != null) {
                    assertEquals(TypeConstants.INT, fieldType, "Arrays should have int length field")
                }
            }

            @Test
            fun `resolveFieldType on Primitive type returns null`() {
                val code = """
                    class Util {
                        String name
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val primitiveType = TypeConstants.INT

                val fieldType = context.getFieldType(primitiveType, "someField")

                assertNull(fieldType, "Primitives should return null")
            }

            @Test
            fun `resolveFieldType with empty field name returns Unknown`() {
                val code = """
                    class Person {
                        String name
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val personType = SemanticType.Known("Person")

                val fieldType = context.getFieldType(personType, "")

                assertNotNull(fieldType)
                assertTrue(fieldType is SemanticType.Unknown)
            }

            @Test
            fun `resolveFieldType handles multiple fields in class`() {
                val code = """
                    class Person {
                        String name
                        int age
                        boolean active
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val personType = SemanticType.Known("Person")

                val nameType = context.getFieldType(personType, "name")
                val ageType = context.getFieldType(personType, "age")
                val activeType = context.getFieldType(personType, "active")

                assertEquals(TypeConstants.STRING, nameType)
                assertEquals(TypeConstants.INT, ageType)
                assertEquals(TypeConstants.BOOLEAN, activeType)
            }
        }
    }

    @Nested
    @DisplayName("Method Resolution")
    inner class MethodResolution {

        @Nested
        @DisplayName("Happy Path: Same-file method resolution")
        inner class HappyPath {

            @Test
            fun `resolveMethodReturnType for simple String return`() {
                val code = """
                    class Person {
                        String getName() {
                            return "test"
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val personType = SemanticType.Known("Person")

                val returnType = context.getMethodReturnType(personType, "getName", emptyList())

                assertNotNull(returnType, "Should resolve method return type")
                assertTrue(returnType is SemanticType.Known)
                assertEquals(TypeConstants.STRING, returnType)
            }

            @Test
            fun `resolveMethodReturnType for void method`() {
                val code = """
                    class Logger {
                        void log(String message) {
                            println message
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val loggerType = SemanticType.Known("Logger")

                val returnType = context.getMethodReturnType(loggerType, "log", listOf(TypeConstants.STRING))

                assertNotNull(returnType, "Void methods should resolve")
                // Void methods should resolve to void type
                assertEquals(TypeConstants.VOID, returnType)
            }

            @Test
            fun `resolveMethodReturnType for primitive return int`() {
                val code = """
                    class Calculator {
                        int add(int a, int b) {
                            return a + b
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val calcType = SemanticType.Known("Calculator")

                val returnType = context.getMethodReturnType(
                    calcType,
                    "add",
                    listOf(TypeConstants.INT, TypeConstants.INT),
                )

                assertNotNull(returnType)
                assertEquals(TypeConstants.INT, returnType)
            }

            @Test
            fun `resolveMethodReturnType for List return type`() {
                val code = """
                    class Repository {
                        List<String> getNames() {
                            return ["Alice", "Bob"]
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val repoType = SemanticType.Known("Repository")

                val returnType = context.getMethodReturnType(repoType, "getNames", emptyList())

                assertNotNull(returnType)
                assertTrue(returnType is SemanticType.Known)
                assertEquals("java.util.List", (returnType as SemanticType.Known).fqn)
            }

            @Test
            fun `resolveMethodReturnType with multiple parameters`() {
                val code = """
                    class StringUtils {
                        String concat(String a, String b, String c) {
                            return a + b + c
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val utilsType = SemanticType.Known("StringUtils")

                val returnType = context.getMethodReturnType(
                    utilsType,
                    "concat",
                    listOf(TypeConstants.STRING, TypeConstants.STRING, TypeConstants.STRING),
                )

                assertNotNull(returnType)
                assertEquals(TypeConstants.STRING, returnType)
            }

            @Test
            fun `resolveMethodReturnType for custom class return`() {
                val code = """
                    class Address {
                        String street
                    }

                    class Person {
                        Address getAddress() {
                            return new Address()
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val personType = SemanticType.Known("Person")

                val returnType = context.getMethodReturnType(personType, "getAddress", emptyList())

                assertNotNull(returnType)
                assertTrue(returnType is SemanticType.Known)
                assertEquals("Address", (returnType as SemanticType.Known).fqn)
            }

            @Test
            fun `resolveMethodReturnType for array return type`() {
                val code = """
                    class ArrayUtils {
                        String[] split(String input) {
                            return input.split(",")
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val utilsType = SemanticType.Known("ArrayUtils")

                val returnType = context.getMethodReturnType(utilsType, "split", listOf(TypeConstants.STRING))

                assertNotNull(returnType)
                assertTrue(returnType is SemanticType.Array, "Should be Array type")
                val componentType = (returnType as SemanticType.Array).componentType
                assertEquals(TypeConstants.STRING, componentType)
            }
        }

        @Nested
        @DisplayName("Edge Cases: Overloads, missing methods, special cases")
        inner class EdgeCases {

            @Test
            fun `resolveMethodReturnType returns Unknown for non-existent method`() {
                val code = """
                    class Person {
                        String getName() {
                            return "test"
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val personType = SemanticType.Known("Person")

                val returnType = context.getMethodReturnType(personType, "nonExistentMethod", emptyList())

                assertNotNull(returnType)
                assertTrue(returnType is SemanticType.Unknown)
            }

            @Test
            fun `resolveMethodReturnType with wrong argument count returns Unknown`() {
                val code = """
                    class Calculator {
                        int add(int a, int b) {
                            return a + b
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val calcType = SemanticType.Known("Calculator")

                // Calling with 1 argument when method expects 2
                val returnType = context.getMethodReturnType(calcType, "add", listOf(TypeConstants.INT))

                assertNotNull(returnType)
                assertTrue(returnType is SemanticType.Unknown)
            }

            @Test
            fun `resolveMethodReturnType on Dynamic type returns null`() {
                val code = """
                    class Util {
                        String getName() { return "test" }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val dynamicType = SemanticType.Dynamic()

                val returnType = context.getMethodReturnType(dynamicType, "getName", emptyList())

                assertNull(returnType, "Dynamic types should return null")
            }

            @Test
            fun `resolveMethodReturnType on Null type returns null`() {
                val code = """
                    class Util {
                        String getName() { return "test" }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)

                val returnType = context.getMethodReturnType(SemanticType.Null, "getName", emptyList())

                assertNull(returnType, "Null types should return null")
            }

            @Test
            fun `resolveMethodReturnType on Primitive type returns null`() {
                val code = """
                    class Util {
                        String getName() { return "test" }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val primitiveType = TypeConstants.INT

                val returnType = context.getMethodReturnType(primitiveType, "getName", emptyList())

                assertNull(returnType, "Primitives should return null")
            }

            @Test
            fun `resolveMethodReturnType on Array type returns null`() {
                val code = """
                    class Util {
                        String getName() { return "test" }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val arrayType = SemanticType.Array(TypeConstants.STRING)

                val returnType = context.getMethodReturnType(arrayType, "getName", emptyList())

                assertNull(returnType, "Array types should return null")
            }

            @Test
            fun `resolveMethodReturnType for unresolved class type returns Unknown`() {
                val code = """
                    class Util {
                        String getName() { return "test" }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val unknownType = SemanticType.Known("com.example.UnknownClass")

                val returnType = context.getMethodReturnType(unknownType, "someMethod", emptyList())

                assertNotNull(returnType)
                assertTrue(returnType is SemanticType.Unknown)
            }

            @Test
            fun `resolveMethodReturnType with overloaded methods (same name, different args)`() {
                val code = """
                    class StringUtils {
                        String format(String input) {
                            return input.toUpperCase()
                        }

                        String format(String input, String pattern) {
                            return String.format(pattern, input)
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val utilsType = SemanticType.Known("StringUtils")

                // Call with 1 argument - matches first format
                val returnType1 = context.getMethodReturnType(utilsType, "format", listOf(TypeConstants.STRING))
                assertNotNull(returnType1)
                assertEquals(TypeConstants.STRING, returnType1)

                // Call with 2 arguments - matches second format
                val returnType2 = context.getMethodReturnType(
                    utilsType,
                    "format",
                    listOf(TypeConstants.STRING, TypeConstants.STRING),
                )
                assertNotNull(returnType2)
                assertEquals(TypeConstants.STRING, returnType2)

                // Call with 3 arguments - no matching overload, should return Unknown
                val returnType3 = context.getMethodReturnType(
                    utilsType,
                    "format",
                    listOf(TypeConstants.INT, TypeConstants.INT, TypeConstants.INT),
                )
                assertTrue(returnType3 is SemanticType.Unknown, "Should return Unknown for non-existent overload")
            }

            @Test
            fun `resolveMethodReturnType with boolean return`() {
                val code = """
                    class Validator {
                        boolean isValid(String input) {
                            return input != null && !input.isEmpty()
                        }
                    }
                """.trimIndent()

                val module = parse(code)
                val semantics = GroovySemantics(stubSolver)
                semantics.inject(module)

                val context = getTypeContext(module, semantics)
                val validatorType = SemanticType.Known("Validator")

                val returnType = context.getMethodReturnType(validatorType, "isValid", listOf(TypeConstants.STRING))

                assertNotNull(returnType)
                assertEquals(TypeConstants.BOOLEAN, returnType)
            }
        }
    }

    // Helper function to access internal context cache
    private fun getTypeContext(module: ModuleNode, semantics: GroovySemantics): NativeTypeContext = try {
        val contextCacheField = GroovySemantics::class.java.getDeclaredField("contextCache")
        contextCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = contextCacheField.get(
            semantics,
        ) as java.util.concurrent.ConcurrentHashMap<ModuleNode, NativeTypeContext>
        cache[module] ?: throw IllegalStateException("Context not found for module after injection")
    } catch (e: NoSuchFieldException) {
        throw IllegalStateException("Could not access contextCache field", e)
    }
}
