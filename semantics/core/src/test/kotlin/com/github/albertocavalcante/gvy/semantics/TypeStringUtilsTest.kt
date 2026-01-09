package com.github.albertocavalcante.gvy.semantics

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
        fun `returns false for concrete types`() {
            assertFalse(TypeStringUtils.isDynamicType("java.lang.String"))
            assertFalse(TypeStringUtils.isDynamicType("java.util.List"))
            assertFalse(TypeStringUtils.isDynamicType("MyClass"))
        }

        @Test
        fun `returns false for class names starting with Unresolved`() {
            // Class names like UnresolvedDependency should NOT be considered dynamic
            // The space/colon check in isValidClasspathTypeName handles error patterns
            assertFalse(TypeStringUtils.isDynamicType("UnresolvedDependency"))
            assertFalse(TypeStringUtils.isDynamicType("UnresolvedException"))
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
            assertEquals("List", TypeStringUtils.normalizeTypeName("List<String>"))
            assertEquals("Map", TypeStringUtils.normalizeTypeName("Map<String, Integer>"))
        }

        @Test
        fun `returns unchanged for non-generic type`() {
            assertEquals("String", TypeStringUtils.normalizeTypeName("String"))
            assertEquals("java.lang.String", TypeStringUtils.normalizeTypeName("java.lang.String"))
        }

        @Test
        fun `handles nested generics`() {
            assertEquals("Map", TypeStringUtils.normalizeTypeName("Map<String, List<Integer>>"))
        }

        @Test
        fun `handles malformed type with unclosed bracket`() {
            // substringBefore('<') handles this gracefully
            assertEquals("List", TypeStringUtils.normalizeTypeName("List<String"))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("", TypeStringUtils.normalizeTypeName(""))
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

        @Test
        fun `returns false for unresolved - it indicates error state not unknown`() {
            // "unresolved" is a dynamic type (error state) but not an "unknown" type
            // Unknown types are intentionally dynamic (Object, def) where we lack type info
            assertFalse(TypeStringUtils.isUnknownType("unresolved"))
        }

        @Test
        fun `returns false for null type - it indicates error state not unknown`() {
            // "null" type string is a dynamic type but not an "unknown" type
            assertFalse(TypeStringUtils.isUnknownType("null"))
        }
    }
}
