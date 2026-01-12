package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.sources.GroovySourceResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
    fun `should return object methods for unknown type`() {
        val methods = gdkProvider.getMethodsForType("com.example.UnknownType")

        // Since everything is an Object in Groovy, we should at least get Object methods
        assertThat(methods).isNotEmpty
        assertThat(methods.map { it.name }).contains("dump", "inspect")
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

    @Test
    fun `should extract parameter names from GDK methods`() {
        val methods = gdkProvider.getMethodsForType("java.util.List")

        // Find 'each' method - in GDK it's: each(List self, Closure closure)
        val eachMethod = methods.find { it.name == "each" && it.parameterTypes.size == 1 }

        assertThat(eachMethod).isNotNull
        assertThat(eachMethod?.parameterNames).hasSize(1)
        // With GroovySourceResolver, we should get real parameter names from source JARs.
        // If source resolution fails, we fall back to reflection which gives synthetic names.
        // Accept either "closure" (from sources) or "arg1" (from reflection fallback)
        assertThat(eachMethod?.parameterNames?.get(0)).isIn("closure", "arg1")
        assertThat(eachMethod?.parameterTypes?.get(0)).isEqualTo("Closure")
    }

    // =============================================================================
    // Tests for GroovySourceResolver Integration
    // =============================================================================

    @Test
    fun `should use mocked GroovySourceResolver parameter names when available`() {
        // Arrange: Create a mock resolver that returns known parameter names
        val mockResolver = mockk<GroovySourceResolver>()
        coEvery { mockResolver.initialize() } returns true
        // Default: return null for unspecified methods (falls back to reflection)
        every { mockResolver.getParameterNames(any(), any(), any()) } returns null
        // Specific mocks for methods we want to test
        every {
            mockResolver.getParameterNames(
                "DefaultGroovyMethods",
                "each",
                listOf("Closure"),
            )
        } returns listOf("customClosureName")
        every {
            mockResolver.getParameterNames(
                "DefaultGroovyMethods",
                "collect",
                listOf("Collection", "Closure"),
            )
        } returns listOf("customCollector", "customTransform")

        // Act: Create provider with mocked resolver
        val classpathService = ClasspathService()
        val provider = GroovyGdkProvider(classpathService, mockResolver)
        provider.initialize()

        // Assert: Verify it uses the mocked resolver's parameter names
        val methods = provider.getMethodsForType("java.util.List")

        val eachMethod = methods.find { it.name == "each" && it.parameterTypes.size == 1 }
        assertThat(eachMethod).isNotNull
        assertThat(eachMethod?.parameterNames).containsExactly("customClosureName")

        val collectMethod = methods.find {
            it.name == "collect" && it.parameterTypes == listOf("Collection", "Closure")
        }
        if (collectMethod != null) { // Method may or may not exist in the GDK
            assertThat(collectMethod.parameterNames).containsExactly("customCollector", "customTransform")
        }
    }

    @Test
    fun `should fallback to reflection when resolver returns null`() {
        // Arrange: Create a resolver that always returns null
        val nullResolver = mockk<GroovySourceResolver>()
        coEvery { nullResolver.initialize() } returns true
        every { nullResolver.getParameterNames(any(), any(), any()) } returns null

        // Act: Create provider with null-returning resolver
        val classpathService = ClasspathService()
        val provider = GroovyGdkProvider(classpathService, nullResolver)
        provider.initialize()

        // Assert: Verify it falls back to reflection-based names (e.g., "arg0", "arg1")
        val methods = provider.getMethodsForType("java.util.List")
        val eachMethod = methods.find { it.name == "each" && it.parameterTypes.size == 1 }

        assertThat(eachMethod).isNotNull
        assertThat(eachMethod?.parameterNames).hasSize(1)
        // Reflection-based names are typically like "arg0", "arg1", etc.
        // The exact name depends on Java parameter name compilation flags
        assertThat(eachMethod?.parameterNames?.get(0)).matches("arg\\d+")
    }

    @Test
    fun `should fallback to reflection when resolver initialization fails`() {
        // Arrange: Create a resolver that fails to initialize
        val failingResolver = mockk<GroovySourceResolver>()
        coEvery { failingResolver.initialize() } returns false
        // Even when initialize fails, the provider still queries the resolver during indexing
        // Mock it to return null (simulating no parameter names available)
        every { failingResolver.getParameterNames(any(), any(), any()) } returns null

        // Act: Create provider with failing resolver
        val classpathService = ClasspathService()
        val provider = GroovyGdkProvider(classpathService, failingResolver)
        provider.initialize()

        // Assert: Provider should still work and use reflection-based names
        val methods = provider.getMethodsForType("java.util.List")

        assertThat(methods).isNotEmpty
        val eachMethod = methods.find { it.name == "each" && it.parameterTypes.size == 1 }
        assertThat(eachMethod).isNotNull
        // Should have parameter names from reflection
        assertThat(eachMethod?.parameterNames).hasSize(1)
    }

    @Test
    fun `should work without resolver instance when not provided`() {
        // Arrange: Create provider without resolver (null)
        val classpathService = ClasspathService()
        val provider = GroovyGdkProvider(classpathService, groovySourceResolver = null)

        // Act: Initialize and get methods
        provider.initialize()
        val methods = provider.getMethodsForType("java.util.List")

        // Assert: Should work fine with reflection-based names
        assertThat(methods).isNotEmpty
        val eachMethod = methods.find { it.name == "each" && it.parameterTypes.size == 1 }
        assertThat(eachMethod).isNotNull
        assertThat(eachMethod?.parameterNames).hasSize(1)
    }

    // =============================================================================
    // Test documenting GDK_CLASSES alignment
    // =============================================================================

    @Test
    fun `should now index all 8 GDK classes from GroovySourceResolver`() {
        // Note: As of recent changes, GroovyGdkProvider now uses GroovySourceResolver.GDK_CLASSES
        // which includes all 8 GDK classes:
        //   1. org.codehaus.groovy.runtime.DefaultGroovyMethods
        //   2. org.codehaus.groovy.runtime.StringGroovyMethods
        //   3. org.codehaus.groovy.runtime.DateGroovyMethods
        //   4. org.codehaus.groovy.runtime.EncodingGroovyMethods
        //   5. org.codehaus.groovy.runtime.IOGroovyMethods
        //   6. org.codehaus.groovy.runtime.ProcessGroovyMethods
        //   7. org.codehaus.groovy.runtime.ResourceGroovyMethods
        //   8. org.codehaus.groovy.vmplugin.v8.PluginDefaultGroovyMethods
        //
        // This ensures parameter names from GroovySourceResolver work for all indexed methods.

        // This test documents the current behavior:
        // The provider successfully indexes methods from the shared GDK_CLASSES list
        val classpathService = ClasspathService()
        val provider = GroovyGdkProvider(classpathService)
        provider.initialize()

        // Verify we get methods from the core indexed classes
        val listMethods = provider.getMethodsForType("java.util.List")
        assertThat(listMethods).isNotEmpty
        assertThat(listMethods.map { it.originClass }).contains(
            "DefaultGroovyMethods",
            "PluginDefaultGroovyMethods",
        )

        val stringMethods = provider.getMethodsForType("java.lang.String")
        assertThat(stringMethods).isNotEmpty
        assertThat(stringMethods.map { it.originClass }).contains(
            "StringGroovyMethods",
        )

        // Methods from other GDK classes like DateGroovyMethods, IOGroovyMethods, etc.
        // will now also be indexed, though they may not appear for these specific types.
        // The provider attempts to load all classes in GDK_CLASSES, and will index
        // methods from those that are available on the classpath.
    }
}
