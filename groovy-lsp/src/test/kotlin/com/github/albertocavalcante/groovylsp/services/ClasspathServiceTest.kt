package com.github.albertocavalcante.groovylsp.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for ClasspathService.
 * Verifies that the service can load and reflect on JDK and Groovy classes.
 */
class ClasspathServiceTest {

    private lateinit var classpathService: ClasspathService

    @BeforeEach
    fun setUp() {
        classpathService = ClasspathService()
    }

    @Test
    fun `should load java_lang_String and extract methods`() {
        val methods = classpathService.getMethods("java.lang.String")

        assertThat(methods).isNotEmpty
        assertThat(methods.map { it.name }).contains(
            "substring",
            "length",
            "toUpperCase",
            "toLowerCase",
            "indexOf",
        )

        // Verify method signatures
        val substring = methods.find { it.name == "substring" && it.parameters.size == 1 }
        assertThat(substring).isNotNull
        assertThat(substring?.returnType).isEqualTo("String")
        assertThat(substring?.isPublic).isTrue
    }

    @Test
    fun `should load java_util_List and extract methods`() {
        val methods = classpathService.getMethods("java.util.List")

        assertThat(methods).isNotEmpty
        assertThat(methods.map { it.name }).contains(
            "add",
            "get",
            "size",
            "remove",
            "clear",
        )

        // Verify 'add' method
        val add = methods.find { it.name == "add" && it.parameters.size == 1 }
        assertThat(add).isNotNull
        assertThat(add?.returnType).isEqualTo("boolean")
    }

    @Test
    fun `should distinguish static and instance methods`() {
        val methods = classpathService.getMethods("java.lang.Integer")

        // Instance method
        val toString = methods.find { it.name == "toString" && it.parameters.isEmpty() }
        assertThat(toString?.isStatic).isFalse

        // Static method
        val parseInt = methods.find { it.name == "parseInt" && it.parameters.size == 1 }
        assertThat(parseInt?.isStatic).isTrue
    }

    @Test
    fun `should return empty list for non-existent class`() {
        val methods = classpathService.getMethods("com.example.NonExistentClass")

        assertThat(methods).isEmpty()
    }

    @Test
    fun `should load class by name`() {
        val stringClass = classpathService.loadClass("java.lang.String")

        assertThat(stringClass).isNotNull
        assertThat(stringClass?.name).isEqualTo("java.lang.String")
    }

    @Test
    fun `should return null for non-existent class when loading`() {
        val nonExistent = classpathService.loadClass("com.example.NonExistentClass")

        assertThat(nonExistent).isNull()
    }

    @Test
    fun `should filter public methods only`() {
        val methods = classpathService.getMethods("java.lang.String")

        // All returned methods should be public
        assertThat(methods.filter { !it.isPublic }).isEmpty()
    }

    @Test
    fun `should index classes from classpath`() {
        classpathService.indexAllClasses()

        // After indexing, we should have indexed classes from libraries (Groovy, AssertJ, etc.)
        // ClassGraph indexes classpath JARs but may not index JRE bootstrap classes
        val results = classpathService.findClassesByPrefix("String")
        assertThat(results).isNotEmpty // Should find library classes starting with "String"
    }

    @Test
    fun `should find classes by prefix case-insensitive`() {
        classpathService.indexAllClasses()

        val results = classpathService.findClassesByPrefix("str")

        assertThat(results).isNotEmpty
        assertThat(results.map { it.simpleName }).anyMatch { it.startsWith("Str", ignoreCase = true) }
    }

    @Test
    fun `should return multiple class variants for same simple name`() {
        classpathService.indexAllClasses()

        // Some class names appear in multiple packages (e.g., various "List", "String", etc. implementations)
        val results = classpathService.findClassesByPrefix("Assert")

        assertThat(results).isNotEmpty
        // Should find classes from test libraries like AssertJ
        assertThat(results).hasSizeGreaterThan(1) // Multiple " Assert" classes exist
    }

    @Test
    fun `should limit results by maxResults parameter`() {
        classpathService.indexAllClasses()

        val results = classpathService.findClassesByPrefix("", maxResults = 10)

        assertThat(results).hasSizeLessThanOrEqualTo(10)
    }

    @Test
    fun `should return sorted results by simple name`() {
        classpathService.indexAllClasses()

        val results = classpathService.findClassesByPrefix("String")

        // Results should be sorted alphabetically
        val names = results.map { it.simpleName }
        assertThat(names).isSorted
    }
}
