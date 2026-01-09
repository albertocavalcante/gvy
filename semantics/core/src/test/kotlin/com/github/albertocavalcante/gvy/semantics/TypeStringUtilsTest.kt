package com.github.albertocavalcante.gvy.semantics

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("TypeStringUtils")
class TypeStringUtilsTest {

    @Nested
    @DisplayName("isDynamicType")
    inner class IsDynamicTypeTests {

        @Test
        fun `returns true for java_lang_Object`() {
            assertTrue(TypeStringUtils.isDynamicType("java.lang.Object"))
        }

        @Test
        fun `returns true for Object`() {
            assertTrue(TypeStringUtils.isDynamicType("Object"))
        }

        @Test
        fun `returns true for def`() {
            assertTrue(TypeStringUtils.isDynamicType("def"))
        }

        @Test
        fun `returns true for unresolved`() {
            assertTrue(TypeStringUtils.isDynamicType("unresolved"))
        }

        @Test
        fun `returns true for null type`() {
            assertTrue(TypeStringUtils.isDynamicType("null"))
        }

        @Test
        fun `returns true for unresolved variable pattern`() {
            assertTrue(TypeStringUtils.isDynamicType("unresolved variable: binding"))
        }

        @Test
        fun `returns true for Unresolved with different case`() {
            assertTrue(TypeStringUtils.isDynamicType("Unresolved"))
            assertTrue(TypeStringUtils.isDynamicType("UNRESOLVED"))
        }

        @Test
        fun `returns false for concrete types`() {
            assertFalse(TypeStringUtils.isDynamicType("java.lang.String"))
            assertFalse(TypeStringUtils.isDynamicType("java.util.List"))
            assertFalse(TypeStringUtils.isDynamicType("MyClass"))
        }
    }

    @Nested
    @DisplayName("isValidClasspathTypeName")
    inner class IsValidClasspathTypeNameTests {

        @Test
        fun `returns false for blank string`() {
            assertFalse(TypeStringUtils.isValidClasspathTypeName(""))
            assertFalse(TypeStringUtils.isValidClasspathTypeName("   "))
        }

        @Test
        fun `returns false for type with spaces`() {
            assertFalse(TypeStringUtils.isValidClasspathTypeName("unresolved variable: binding"))
        }

        @Test
        fun `returns false for type with colons`() {
            assertFalse(TypeStringUtils.isValidClasspathTypeName("error: unknown type"))
        }

        @Test
        fun `returns false for dynamic types`() {
            assertFalse(TypeStringUtils.isValidClasspathTypeName("java.lang.Object"))
            assertFalse(TypeStringUtils.isValidClasspathTypeName("Object"))
            assertFalse(TypeStringUtils.isValidClasspathTypeName("def"))
        }

        @Test
        fun `returns true for valid FQN`() {
            assertTrue(TypeStringUtils.isValidClasspathTypeName("java.lang.String"))
            assertTrue(TypeStringUtils.isValidClasspathTypeName("java.util.ArrayList"))
        }

        @Test
        fun `returns true for simple class name`() {
            // Simple names like MyClass are valid for classpath lookup
            // (though they may fail at runtime if not found)
            assertTrue(TypeStringUtils.isValidClasspathTypeName("MyClass"))
        }
    }

    @Nested
    @DisplayName("normalizeTypeName")
    inner class NormalizeTypeNameTests {

        @Test
        fun `strips generic parameters`() {
            kotlin.test.assertEquals("List", TypeStringUtils.normalizeTypeName("List<String>"))
            kotlin.test.assertEquals("Map", TypeStringUtils.normalizeTypeName("Map<String, Integer>"))
        }

        @Test
        fun `returns unchanged for non-generic type`() {
            kotlin.test.assertEquals("String", TypeStringUtils.normalizeTypeName("String"))
            kotlin.test.assertEquals("java.lang.String", TypeStringUtils.normalizeTypeName("java.lang.String"))
        }
    }

    @Nested
    @DisplayName("isUnknownType")
    inner class IsUnknownTypeTests {

        @Test
        fun `returns true for null`() {
            assertTrue(TypeStringUtils.isUnknownType(null))
        }

        @Test
        fun `returns true for Object types`() {
            assertTrue(TypeStringUtils.isUnknownType("java.lang.Object"))
            assertTrue(TypeStringUtils.isUnknownType("Object"))
        }

        @Test
        fun `returns true for def`() {
            assertTrue(TypeStringUtils.isUnknownType("def"))
        }

        @Test
        fun `returns false for concrete types`() {
            assertFalse(TypeStringUtils.isUnknownType("java.lang.String"))
            assertFalse(TypeStringUtils.isUnknownType("int"))
        }
    }
}
