package com.github.albertocavalcante.groovylsp.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for GroovyGdkProvider.
 * Verifies that the provider correctly indexes and returns GDK extension methods.
 */
class GroovyGdkProviderTest {

    private lateinit var classpathService: ClasspathService
    private lateinit var gdkProvider: GroovyGdkProvider

    @BeforeEach
    fun setUp() {
        classpathService = ClasspathService()
        gdkProvider = GroovyGdkProvider(classpathService)
        gdkProvider.initialize()
    }

    @Test
    fun `should initialize and build GDK index`() {
        // After initialization, the cache should have entries
        val listMethods = gdkProvider.getMethodsForType("java.util.List")

        assertThat(listMethods).isNotEmpty
    }

    @Test
    fun `should return GDK methods for java_util_List`() {
        val methods = gdkProvider.getMethodsForType("java.util.List")

        // Common Groovy GDK methods for List - using methods we know exist from test output
        assertThat(methods.map { it.name }).contains(
            "each",
            "find",
            "findAll",
            "plus",
            "minus",
            "reverse",
        )
    }

    @Test
    fun `should return GDK methods for java_lang_String`() {
        val methods = gdkProvider.getMethodsForType("java.lang.String")

        // Common Groovy GDK methods for String - using methods that actually exist
        assertThat(methods.map { it.name }).contains(
            "takeAfter",
            "takeBefore",
            "size",
        )
    }

    @Test
    fun `should synthesize parameters correctly`() {
        val methods = gdkProvider.getMethodsForType("java.util.List")

        // Find 'each' method - in GDK it's: each(List self, Closure closure)
        // After synthesis it should be: each(Closure closure)
        val eachMethod = methods.find { it.name == "each" && it.parameters.size == 1 }

        assertThat(eachMethod).isNotNull
        assertThat(eachMethod?.parameters).hasSize(1)
        assertThat(eachMethod?.parameters?.get(0)).isEqualTo("Closure")
    }

    @Test
    fun `should handle class hierarchy`() {
        // ArrayList should get methods from List, Collection, and Iterable
        val arrayListMethods = gdkProvider.getMethodsForType("java.util.ArrayList")

        // Methods that are defined for Collection or Iterable should appear
        assertThat(arrayListMethods.map { it.name }).contains(
            "each",
            "find",
            "plus",
        )
    }

    @Test
    fun `should deduplicate methods by signature`() {
        val methods = gdkProvider.getMethodsForType("java.util.List")

        // Count unique method signatures (name + parameters)
        val signatures = methods.map { "${it.name}(${it.parameters.joinToString(",")})" }

        // No duplicate signatures should exist
        assertThat(signatures.size).isEqualTo(signatures.distinct().size)
    }

    @Test
    fun `should include origin class in method info`() {
        val methods = gdkProvider.getMethodsForType("java.util.List")

        val eachMethod = methods.find { it.name == "each" && it.parameters.size == 1 }

        assertThat(eachMethod).isNotNull
        assertThat(eachMethod?.originClass).isEqualTo("DefaultGroovyMethods")
    }

    @Test
    fun `should return empty list for unknown type`() {
        val methods = gdkProvider.getMethodsForType("com.example.UnknownType")

        assertThat(methods).isEmpty()
    }

    @Test
    fun `should handle common type fallbacks when class loader fails`() {
        // Even if we can't load ArrayList directly, we should still get List methods
        val methods = gdkProvider.getMethodsForType("java.util.ArrayList")

        // Should include methods from Collection/List/Iterable
        assertThat(methods).isNotEmpty
    }

    @Test
    fun `should include documentation for GDK methods`() {
        val methods = gdkProvider.getMethodsForType("java.util.List")

        val eachMethod = methods.find { it.name == "each" }

        assertThat(eachMethod?.doc).isNotBlank
        assertThat(eachMethod?.doc).contains("Groovy GDK method")
    }
}
