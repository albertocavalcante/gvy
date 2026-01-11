package com.github.albertocavalcante.groovylsp.sources

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for GdkMethodIndex.
 */
class GdkMethodIndexTest {

    private lateinit var index: GdkMethodIndex

    @BeforeEach
    fun setUp() {
        index = GdkMethodIndex()
    }

    @Test
    fun `should add and retrieve method parameters`() {
        // Given
        index.addMethod(
            className = "DefaultGroovyMethods",
            methodName = "each",
            parameterTypes = listOf("Closure"),
            parameterNames = listOf("closure"),
        )

        // When
        val paramNames = index.getParameterNames(
            className = "DefaultGroovyMethods",
            methodName = "each",
            parameterTypes = listOf("Closure"),
        )

        // Then
        assertThat(paramNames).isEqualTo(listOf("closure"))
    }

    @Test
    fun `should handle methods with multiple parameters`() {
        // Given
        index.addMethod(
            className = "DefaultGroovyMethods",
            methodName = "findAll",
            parameterTypes = listOf("Closure"),
            parameterNames = listOf("predicate"),
        )

        // When
        val paramNames = index.getParameterNames(
            className = "DefaultGroovyMethods",
            methodName = "findAll",
            parameterTypes = listOf("Closure"),
        )

        // Then
        assertThat(paramNames).isEqualTo(listOf("predicate"))
    }

    @Test
    fun `should handle method overloads with different signatures`() {
        // Given - add two overloads of the same method
        index.addMethod(
            className = "DefaultGroovyMethods",
            methodName = "collect",
            parameterTypes = listOf("Closure"),
            parameterNames = listOf("transform"),
        )

        index.addMethod(
            className = "DefaultGroovyMethods",
            methodName = "collect",
            parameterTypes = listOf("Collection", "Closure"),
            parameterNames = listOf("collector", "transform"),
        )

        // When
        val paramNames1 = index.getParameterNames(
            className = "DefaultGroovyMethods",
            methodName = "collect",
            parameterTypes = listOf("Closure"),
        )

        val paramNames2 = index.getParameterNames(
            className = "DefaultGroovyMethods",
            methodName = "collect",
            parameterTypes = listOf("Collection", "Closure"),
        )

        // Then
        assertThat(paramNames1).isEqualTo(listOf("transform"))
        assertThat(paramNames2).isEqualTo(listOf("collector", "transform"))
    }

    @Test
    fun `should return null for unknown method`() {
        // When
        val paramNames = index.getParameterNames(
            className = "DefaultGroovyMethods",
            methodName = "nonExistent",
            parameterTypes = listOf("String"),
        )

        // Then
        assertThat(paramNames).isNull()
    }

    @Test
    fun `should handle different GDK classes`() {
        // Given
        index.addMethod(
            className = "DefaultGroovyMethods",
            methodName = "each",
            parameterTypes = listOf("Closure"),
            parameterNames = listOf("closure"),
        )

        index.addMethod(
            className = "StringGroovyMethods",
            methodName = "takeAfter",
            parameterTypes = listOf("String"),
            parameterNames = listOf("delimiter"),
        )

        // When
        val paramNames1 = index.getParameterNames(
            className = "DefaultGroovyMethods",
            methodName = "each",
            parameterTypes = listOf("Closure"),
        )

        val paramNames2 = index.getParameterNames(
            className = "StringGroovyMethods",
            methodName = "takeAfter",
            parameterTypes = listOf("String"),
        )

        // Then
        assertThat(paramNames1).isEqualTo(listOf("closure"))
        assertThat(paramNames2).isEqualTo(listOf("delimiter"))
    }

    @Test
    fun `should skip methods with no parameters`() {
        // Given - addMethod is called with empty parameter lists
        index.addMethod(
            className = "StringGroovyMethods",
            methodName = "size",
            parameterTypes = emptyList(),
            parameterNames = emptyList(),
        )

        // When
        val paramNames = index.getParameterNames(
            className = "StringGroovyMethods",
            methodName = "size",
            parameterTypes = emptyList(),
        )

        // Then - the method should not be indexed
        assertThat(paramNames).isNull()
    }

    @Test
    fun `should be thread-safe`() {
        // Given - simulate concurrent access
        val threads = (1..10).map { threadId ->
            Thread {
                repeat(100) { iteration ->
                    index.addMethod(
                        className = "TestClass$threadId",
                        methodName = "method$iteration",
                        parameterTypes = listOf("String"),
                        parameterNames = listOf("param$iteration"),
                    )
                }
            }
        }

        // When
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Then - verify we can retrieve all added methods
        val stats = index.getStatistics()
        val indexedMethods = stats["indexedMethods"] as Int

        // We should have 10 threads * 100 methods = 1000 methods
        assertThat(indexedMethods).isEqualTo(1000)
    }

    @Test
    fun `should provide statistics`() {
        // Given
        index.addMethod(
            className = "DefaultGroovyMethods",
            methodName = "each",
            parameterTypes = listOf("Closure"),
            parameterNames = listOf("closure"),
        )

        index.addMethod(
            className = "DefaultGroovyMethods",
            methodName = "collect",
            parameterTypes = listOf("Closure"),
            parameterNames = listOf("transform"),
        )

        // When
        val stats = index.getStatistics()

        // Then
        assertThat(stats["indexedMethods"]).isEqualTo(2)
        assertThat(stats["sampleKeys"]).isNotNull
    }

    @Test
    fun `should clear the index`() {
        // Given
        index.addMethod(
            className = "DefaultGroovyMethods",
            methodName = "each",
            parameterTypes = listOf("Closure"),
            parameterNames = listOf("closure"),
        )

        // When
        index.clear()

        // Then
        val paramNames = index.getParameterNames(
            className = "DefaultGroovyMethods",
            methodName = "each",
            parameterTypes = listOf("Closure"),
        )

        assertThat(paramNames).isNull()

        val stats = index.getStatistics()
        assertThat(stats["indexedMethods"]).isEqualTo(0)
    }
}
