package com.github.albertocavalcante.groovylsp.sources

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Test
    fun `should throw IllegalArgumentException when parameter types and names sizes mismatch`() {
        // Given - mismatched parameter types and names
        val className = "DefaultGroovyMethods"
        val methodName = "testMethod"
        val parameterTypes = listOf("String", "Integer")
        val parameterNames = listOf("param1")

        // When/Then - should throw IllegalArgumentException
        assertThatThrownBy {
            index.addMethod(
                className = className,
                methodName = methodName,
                parameterTypes = parameterTypes,
                parameterNames = parameterNames,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Parameter types and names must have same size")
            .hasMessageContaining("DefaultGroovyMethods.testMethod")
    }

    @Test
    fun `should throw IllegalArgumentException when more names than types`() {
        // Given - more parameter names than types
        val className = "DefaultGroovyMethods"
        val methodName = "testMethod"
        val parameterTypes = listOf("String")
        val parameterNames = listOf("param1", "param2", "param3")

        // When/Then - should throw IllegalArgumentException
        assertThatThrownBy {
            index.addMethod(
                className = className,
                methodName = methodName,
                parameterTypes = parameterTypes,
                parameterNames = parameterNames,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Parameter types and names must have same size")
    }

    @Test
    fun `should handle empty class name`() {
        // Given - empty class name
        val className = ""
        val methodName = "testMethod"
        val parameterTypes = listOf("String")
        val parameterNames = listOf("param")

        // When - add method with empty class name
        index.addMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
            parameterNames = parameterNames,
        )

        // Then - should be able to retrieve by empty class name
        val paramNames = index.getParameterNames(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        )

        assertThat(paramNames).isEqualTo(listOf("param"))

        // TODO: Consider validating that className is not empty for better error messages
    }

    @Test
    fun `should handle empty method name`() {
        // Given - empty method name
        val className = "DefaultGroovyMethods"
        val methodName = ""
        val parameterTypes = listOf("String")
        val parameterNames = listOf("param")

        // When - add method with empty method name
        index.addMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
            parameterNames = parameterNames,
        )

        // Then - should be able to retrieve by empty method name
        val paramNames = index.getParameterNames(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        )

        assertThat(paramNames).isEqualTo(listOf("param"))

        // TODO: Consider validating that methodName is not empty for better error messages
    }

    @Test
    fun `should handle special characters in class and method names`() {
        // Given - special characters in names
        val className = "Test\$Inner.Class"
        val methodName = "method\$with_special-chars"
        val parameterTypes = listOf("String", "Integer")
        val parameterNames = listOf("param\$1", "param_2")

        // When - add method with special characters
        index.addMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
            parameterNames = parameterNames,
        )

        // Then - should be able to retrieve with special characters
        val paramNames = index.getParameterNames(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        )

        assertThat(paramNames).isEqualTo(listOf("param\$1", "param_2"))
    }

    @Test
    fun `should handle special characters in parameter types`() {
        // Given - special characters in parameter types (generics, arrays)
        val className = "DefaultGroovyMethods"
        val methodName = "complexMethod"
        val parameterTypes = listOf("List<String>", "Map<String,Integer>", "String[]")
        val parameterNames = listOf("list", "map", "array")

        // When - add method with complex parameter types
        index.addMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
            parameterNames = parameterNames,
        )

        // Then - should be able to retrieve with complex parameter types
        val paramNames = index.getParameterNames(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        )

        assertThat(paramNames).isEqualTo(listOf("list", "map", "array"))
    }

    @Test
    fun `should handle very long parameter lists`() {
        // Given - method with 15 parameters
        val className = "DefaultGroovyMethods"
        val methodName = "methodWithManyParams"
        val parameterTypes = (1..15).map { "Type$it" }
        val parameterNames = (1..15).map { "param$it" }

        // When - add method with many parameters
        index.addMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
            parameterNames = parameterNames,
        )

        // Then - should correctly store and retrieve all parameters
        val paramNames = index.getParameterNames(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        )

        assertThat(paramNames).isEqualTo(parameterNames)
        assertThat(paramNames).hasSize(15)
    }

    @Test
    fun `should handle parameter lists with exactly 10 parameters`() {
        // Given - method with exactly 10 parameters
        val className = "DefaultGroovyMethods"
        val methodName = "methodWith10Params"
        val parameterTypes = (1..10).map { "Type$it" }
        val parameterNames = (1..10).map { "param$it" }

        // When - add method with 10 parameters
        index.addMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
            parameterNames = parameterNames,
        )

        // Then - should correctly store and retrieve all parameters
        val paramNames = index.getParameterNames(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        )

        assertThat(paramNames).isEqualTo(parameterNames)
        assertThat(paramNames).hasSize(10)
    }

    @Test
    fun `should handle parameter lists with 20 parameters`() {
        // Given - method with 20 parameters (stress test)
        val className = "DefaultGroovyMethods"
        val methodName = "methodWith20Params"
        val parameterTypes = (1..20).map { "Type$it" }
        val parameterNames = (1..20).map { "param$it" }

        // When - add method with 20 parameters
        index.addMethod(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
            parameterNames = parameterNames,
        )

        // Then - should correctly store and retrieve all parameters
        val paramNames = index.getParameterNames(
            className = className,
            methodName = methodName,
            parameterTypes = parameterTypes,
        )

        assertThat(paramNames).isEqualTo(parameterNames)
        assertThat(paramNames).hasSize(20)

        // TODO: Consider documenting maximum supported parameter count if there are practical limits
    }
}
