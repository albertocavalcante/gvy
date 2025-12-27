package com.github.albertocavalcante.groovylsp.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Unit tests for ClasspathService.
 * Verifies that the service can load and reflect on JDK and Groovy classes.
 */
class ClasspathServiceTest {

    private lateinit var classpathService: ClasspathService

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        classpathService = ClasspathService(defaultIndex())
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
    fun `should include default methods like forEach for ArrayList`() {
        val methods = classpathService.getMethods("java.util.ArrayList")

        val forEach = methods.find { it.name == "forEach" }
        assertThat(forEach).isNotNull
        assertThat(forEach?.parameters).contains("Consumer")
    }

    @Test
    fun `should include default methods like forEach for List interface`() {
        val methods = classpathService.getMethods("java.util.List")

        val forEach = methods.find { it.name == "forEach" }
        assertThat(forEach).isNotNull
        assertThat(forEach?.parameters).contains("Consumer")
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

        val results = classpathService.findClassesByPrefix("String")
        assertThat(results).isNotEmpty
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

    @Test
    fun `should return deterministic class variants`() {
        val index = ClasspathService.ClassIndex()
        index.add("List", "java.util.List")
        index.add("List", "java.awt.List")
        index.add("List", "java.util.List")
        index.add("Map", "java.util.Map")

        val snapshot = index.snapshot()

        assertThat(snapshot["List"]).containsExactly("java.awt.List", "java.util.List")
        assertThat(snapshot["Map"]).containsExactly("java.util.Map")
    }

    @Test
    fun `uses provided classpath index`() {
        val fakeIndex = FakeClasspathIndex(
            listOf(
                IndexedClass("Fake", "com.example.Fake"),
                IndexedClass("Fake", "com.example.Fake"),
                IndexedClass("Fake", "com.other.Fake"),
            ),
        )
        val service = ClasspathService(fakeIndex)

        val results = service.findClassesByPrefix("Fake")

        assertThat(results.map { it.fullName }).containsExactly(
            "com.example.Fake",
            "com.other.Fake",
        )
    }

    @Test
    fun `passes classpath entries to indexer`() {
        val root = tempDir.resolve("entries")
        val first = root.resolve("first")
        val second = root.resolve("second")

        root.toFile().mkdirs()
        first.toFile().mkdirs()
        second.toFile().mkdirs()

        val fakeIndex = RecordingClasspathIndex()
        val service = ClasspathService(fakeIndex)

        service.updateClasspath(listOf(first, second))
        service.indexAllClasses()

        assertThat(fakeIndex.entries).containsExactly(first.toString(), second.toString())
    }

    @Test
    fun `uses classloader urls when classpath entries are empty`() {
        val root = tempDir.resolve("loader")
        val first = root.resolve("first")
        val second = root.resolve("second")

        root.toFile().mkdirs()
        first.toFile().mkdirs()
        second.toFile().mkdirs()

        val fakeIndex = RecordingClasspathIndex()
        val service = ClasspathService(fakeIndex)

        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            setCurrentClassLoader(service, loader)
            service.indexAllClasses()
        }

        assertThat(fakeIndex.entries).containsExactly(first.toString(), second.toString())
    }

    @Test
    fun `uses provided reflection for methods`() {
        val reflection = RecordingClasspathReflection()
        val service = ClasspathService(defaultIndex(), reflection)

        service.getMethods("java.lang.String")

        assertThat(reflection.methodCalls).containsExactly("java.lang.String")
    }

    @Test
    fun `uses provided reflection for class loading`() {
        val reflection = RecordingClasspathReflection().apply {
            loadResult = String::class.java
        }
        val service = ClasspathService(defaultIndex(), reflection)

        val loaded = service.loadClass("java.lang.String")

        assertThat(loaded).isEqualTo(String::class.java)
        assertThat(reflection.loadCalls).containsExactly("java.lang.String")
    }
}

private fun defaultIndex(): ClasspathIndex = FakeClasspathIndex(
    listOf(
        IndexedClass("StringArbitrary", "com.example.StringArbitrary"),
        IndexedClass("String", "java.lang.String"),
        IndexedClass("Stringable", "com.example.Stringable"),
        IndexedClass("Assert", "org.assertj.core.api.Assert"),
        IndexedClass("Assert", "org.junit.Assert"),
    ),
)

private class FakeClasspathIndex(private val entries: List<IndexedClass>) : ClasspathIndex {
    override fun index(classpathEntries: List<String>): List<IndexedClass> = entries
}

private class RecordingClasspathIndex : ClasspathIndex {
    val entries = mutableListOf<String>()

    override fun index(classpathEntries: List<String>): List<IndexedClass> {
        entries.clear()
        entries.addAll(classpathEntries)
        return emptyList()
    }
}

private class RecordingClasspathReflection : ClasspathReflection {
    val methodCalls = mutableListOf<String>()
    val loadCalls = mutableListOf<String>()
    var loadResult: Class<*>? = null

    override fun getMethods(className: String): List<ReflectedMethod> {
        methodCalls.add(className)
        return emptyList()
    }

    override fun loadClass(className: String): Class<*>? {
        loadCalls.add(className)
        return loadResult
    }
}

private fun setCurrentClassLoader(service: ClasspathService, classLoader: ClassLoader) {
    val field = ClasspathService::class.java.getDeclaredField("currentClassLoader")
    field.isAccessible = true
    field.set(service, classLoader)
}
