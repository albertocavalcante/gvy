package com.github.albertocavalcante.groovycommon.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TDD tests for NameConversions utilities.
 * These tests define the expected behavior before implementation.
 */
class NameConversionsTest {

    // ==========================================================================
    // simpleClassName() tests
    // ==========================================================================

    @Test
    fun `simpleClassName extracts class from FQN`() {
        assertEquals("ArrayList", "java.util.ArrayList".simpleClassName())
    }

    @Test
    fun `simpleClassName handles nested packages`() {
        assertEquals("TypeInferencer", "com.github.albertocavalcante.groovyparser.ast.TypeInferencer".simpleClassName())
    }

    @Test
    fun `simpleClassName returns input when no dot`() {
        assertEquals("String", "String".simpleClassName())
    }

    @Test
    fun `simpleClassName handles empty string`() {
        assertEquals("", "".simpleClassName())
    }

    @Test
    fun `simpleClassName handles trailing dot`() {
        assertEquals("", "java.util.".simpleClassName())
    }

    // ==========================================================================
    // toLowerCamelCase() tests
    // ==========================================================================

    @Test
    fun `toLowerCamelCase lowercases first char`() {
        assertEquals("myClass", "MyClass".toLowerCamelCase())
    }

    @Test
    fun `toLowerCamelCase preserves already lowercase`() {
        assertEquals("already", "already".toLowerCamelCase())
    }

    @Test
    fun `toLowerCamelCase handles single char uppercase`() {
        assertEquals("a", "A".toLowerCamelCase())
    }

    @Test
    fun `toLowerCamelCase handles single char lowercase`() {
        assertEquals("a", "a".toLowerCamelCase())
    }

    @Test
    fun `toLowerCamelCase handles empty string`() {
        assertEquals("", "".toLowerCamelCase())
    }

    @Test
    fun `toLowerCamelCase preserves rest of string`() {
        assertEquals("uRLConnection", "URLConnection".toLowerCamelCase())
    }

    // ==========================================================================
    // toStepName() tests
    // ==========================================================================

    @Test
    fun `toStepName removes Step suffix`() {
        assertEquals("shell", "ShellStep".toStepName())
    }

    @Test
    fun `toStepName removes Builder suffix`() {
        assertEquals("docker", "DockerBuilder".toStepName())
    }

    @Test
    fun `toStepName removes Descriptor suffix`() {
        assertEquals("git", "GitDescriptor".toStepName())
    }

    @Test
    fun `toStepName chains suffix removal and lowercases`() {
        assertEquals("myCustom", "MyCustomStep".toStepName())
    }

    @Test
    fun `toStepName handles class with no suffix`() {
        assertEquals("echo", "Echo".toStepName())
    }

    @Test
    fun `toStepName handles already lowercase`() {
        assertEquals("sh", "sh".toStepName())
    }

    // ==========================================================================
    // toPropertyName() tests
    // ==========================================================================

    @Test
    fun `toPropertyName converts setFoo to foo`() {
        assertEquals("userName", "setUserName".toPropertyName())
    }

    @Test
    fun `toPropertyName converts setSingleChar to singleChar`() {
        assertEquals("x", "setX".toPropertyName())
    }

    @Test
    fun `toPropertyName handles no set prefix`() {
        assertEquals("notASetter", "notASetter".toPropertyName())
    }

    @Test
    fun `toPropertyName handles just set`() {
        assertEquals("", "set".toPropertyName())
    }

    // ==========================================================================
    // extractSymbolName() tests
    // ==========================================================================

    @Test
    fun `extractSymbolName handles String value`() {
        assertEquals("myStep", extractSymbolName("myStep"))
    }

    @Test
    fun `extractSymbolName handles Array value`() {
        assertEquals("first", extractSymbolName(arrayOf("first", "second")))
    }

    @Test
    fun `extractSymbolName handles List value`() {
        assertEquals("fromList", extractSymbolName(listOf("fromList", "other")))
    }

    @Test
    fun `extractSymbolName returns null for blank string`() {
        assertNull(extractSymbolName(""))
    }

    @Test
    fun `extractSymbolName returns null for whitespace`() {
        assertNull(extractSymbolName("   "))
    }

    @Test
    fun `extractSymbolName returns null for null`() {
        assertNull(extractSymbolName(null))
    }

    @Test
    fun `extractSymbolName handles empty array`() {
        assertNull(extractSymbolName(emptyArray<String>()))
    }

    @Test
    fun `extractSymbolName handles empty list`() {
        assertNull(extractSymbolName(emptyList<String>()))
    }

    // ==========================================================================
    // formatTypeName() tests
    // ==========================================================================

    @Test
    fun `formatTypeName simplifies fully qualified name`() {
        assertEquals("String", "java.lang.String".formatTypeName())
    }

    @Test
    fun `formatTypeName handles simple name without package`() {
        assertEquals("String", "String".formatTypeName())
    }

    @Test
    fun `formatTypeName handles empty string`() {
        assertEquals("", "".formatTypeName())
    }

    @Test
    fun `formatTypeName handles generic type with single param`() {
        assertEquals("ArrayList<Integer>", "java.util.ArrayList<java.lang.Integer>".formatTypeName())
    }

    @Test
    fun `formatTypeName handles generic type with multiple params`() {
        assertEquals("Map<String, Object>", "java.util.Map<java.lang.String, java.lang.Object>".formatTypeName())
    }

    @Test
    fun `formatTypeName handles nested generic types`() {
        assertEquals(
            "Map<String, List<Integer>>",
            "java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>".formatTypeName(),
        )
    }

    @Test
    fun `formatTypeName handles nested multiple generic types`() {
        val input = "java.util.Map<java.lang.String, java.util.Map<java.lang.String, java.lang.Integer>>"
        assertEquals("Map<String, Map<String, Integer>>", input.formatTypeName())
    }

    @Test
    fun `formatTypeName handles nested generics with multiple params`() {
        val input = "java.util.Map<java.lang.String, " +
            "java.util.function.Function<java.lang.String, java.util.List<java.lang.String>>>"
        assertEquals(
            "Map<String, Function<String, List<String>>>",
            input.formatTypeName(),
        )
    }

    @Test
    fun `formatTypeName handles bounded wildcards`() {
        assertEquals("List<? extends Number>", "java.util.List<? extends java.lang.Number>".formatTypeName())
        assertEquals("? super String", "? super java.lang.String".formatTypeName())
    }

    @Test
    fun `formatTypeName handles primitive types`() {
        assertEquals("int", "int".formatTypeName())
        assertEquals("boolean", "boolean".formatTypeName())
    }

    @Test
    fun `formatTypeName handles arrays in type parameters`() {
        // Note: This is a simplified test - Groovy arrays may be represented differently
        assertEquals("List<String[]>", "java.util.List<java.lang.String[]>".formatTypeName())
    }

    @Test
    fun `formatTypeName handles deeply nested packages`() {
        assertEquals(
            "TypeInferencer",
            "com.github.albertocavalcante.groovyparser.ast.TypeInferencer".formatTypeName(),
        )
    }

    @Test
    fun `formatTypeName preserves wildcard types`() {
        // Wildcards may appear in some type representations
        assertEquals("List<?>", "java.util.List<?>".formatTypeName())
    }

    @Test
    fun `formatTypeName handles simple generic without FQN`() {
        assertEquals("List<String>", "List<String>".formatTypeName())
    }
}
