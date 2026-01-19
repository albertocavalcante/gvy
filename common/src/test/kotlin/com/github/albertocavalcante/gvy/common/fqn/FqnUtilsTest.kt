package com.github.albertocavalcante.gvy.common.fqn

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("FqnUtils")
class FqnUtilsTest {

    @Nested
    @DisplayName("packageName")
    inner class PackageNameTests {

        @Test
        fun `extracts package from standard FQN with dots`() {
            assertEquals("java.util", packageName("java.util.ArrayList"))
            assertEquals("java.lang", packageName("java.lang.String"))
            assertEquals("com.example", packageName("com.example.MyClass"))
        }

        @Test
        fun `extracts package from FQN with slashes`() {
            assertEquals("java/util", packageName("java/util/ArrayList"))
            assertEquals("java/lang", packageName("java/lang/String"))
            assertEquals("com/example", packageName("com/example/MyClass"))
        }

        @Test
        fun `handles class without package`() {
            assertEquals("", packageName("String"))
            assertEquals("", packageName("MyClass"))
            assertEquals("", packageName("ArrayList"))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("", packageName(""))
        }

        @Test
        fun `handles nested packages`() {
            assertEquals("com.example.sub.deep", packageName("com.example.sub.deep.MyClass"))
            assertEquals("a.b.c.d.e", packageName("a.b.c.d.e.F"))
        }

        @Test
        fun `handles single-level package`() {
            assertEquals("com", packageName("com.MyClass"))
            assertEquals("java", packageName("java.String"))
        }

        @Test
        fun `preserves separator type in result`() {
            assertEquals("com.example", packageName("com.example.MyClass"))
            assertEquals("com/example", packageName("com/example/MyClass"))
        }

        @Test
        fun `handles class name with numbers`() {
            assertEquals("com.example", packageName("com.example.Class123"))
            assertEquals("com.example.v2", packageName("com.example.v2.MyClass"))
        }

        @Test
        fun `handles class name with underscores`() {
            assertEquals("com.example", packageName("com.example.My_Class"))
            assertEquals("com.example", packageName("com.example.MY_CLASS"))
        }

        @Test
        fun `handles inner classes with dollar sign`() {
            // Inner classes typically use $ separator, but this function treats them as part of the class name
            assertEquals("com.example", packageName("com.example.Outer\$Inner"))
        }
    }

    @Nested
    @DisplayName("toPackageName extension")
    inner class ToPackageNameTests {

        @Test
        fun `extension function works like packageName`() {
            assertEquals("java.util", "java.util.ArrayList".toPackageName())
            assertEquals("com.example", "com.example.MyClass".toPackageName())
            assertEquals("", "String".toPackageName())
            assertEquals("", "".toPackageName())
        }

        @Test
        fun `extension function handles slashes`() {
            assertEquals("java/util", "java/util/ArrayList".toPackageName())
            assertEquals("com/example", "com/example/MyClass".toPackageName())
        }

        @Test
        fun `extension function handles nested packages`() {
            assertEquals("com.example.sub.deep", "com.example.sub.deep.MyClass".toPackageName())
        }
    }
}
