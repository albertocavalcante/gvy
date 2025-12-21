package com.github.albertocavalcante.groovygdsl.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * TDD tests for GDSL descriptor data classes.
 *
 * These tests define the expected behavior before implementation.
 */
class DescriptorsTest {

    @Nested
    inner class ParameterDescriptorTest {

        @Test
        fun `creates parameter with name and type`() {
            val param = ParameterDescriptor(name = "script", type = "java.lang.String")

            assertThat(param.name).isEqualTo("script")
            assertThat(param.type).isEqualTo("java.lang.String")
            assertThat(param.documentation).isNull()
        }

        @Test
        fun `creates parameter with documentation`() {
            val param = ParameterDescriptor(
                name = "message",
                type = "String",
                documentation = "The message to print",
            )

            assertThat(param.documentation).isEqualTo("The message to print")
        }

        @Test
        fun `equality based on all fields`() {
            val param1 = ParameterDescriptor("name", "String", "doc")
            val param2 = ParameterDescriptor("name", "String", "doc")
            val param3 = ParameterDescriptor("name", "String", "different")

            assertThat(param1).isEqualTo(param2)
            assertThat(param1).isNotEqualTo(param3)
        }

        @Test
        fun `copy with modified fields`() {
            val original = ParameterDescriptor("name", "String")
            val modified = original.copy(type = "int")

            assertThat(modified.name).isEqualTo("name")
            assertThat(modified.type).isEqualTo("int")
        }
    }

    @Nested
    inner class NamedParameterDescriptorTest {

        @Test
        fun `creates named parameter with defaults`() {
            val param = NamedParameterDescriptor(name = "returnStdout", type = "boolean")

            assertThat(param.name).isEqualTo("returnStdout")
            assertThat(param.type).isEqualTo("boolean")
            assertThat(param.required).isFalse()
            assertThat(param.defaultValue).isNull()
            assertThat(param.documentation).isNull()
        }

        @Test
        fun `creates required named parameter with default value`() {
            val param = NamedParameterDescriptor(
                name = "script",
                type = "String",
                required = true,
                defaultValue = null,
                documentation = "The shell script to execute",
            )

            assertThat(param.required).isTrue()
        }

        @Test
        fun `creates named parameter with default value`() {
            val param = NamedParameterDescriptor(
                name = "returnStdout",
                type = "boolean",
                defaultValue = "false",
            )

            assertThat(param.defaultValue).isEqualTo("false")
        }
    }

    @Nested
    inner class MethodDescriptorTest {

        @Test
        fun `creates method with name and return type`() {
            val method = MethodDescriptor(name = "echo", returnType = "void")

            assertThat(method.name).isEqualTo("echo")
            assertThat(method.returnType).isEqualTo("void")
            assertThat(method.parameters).isEmpty()
            assertThat(method.namedParameters).isEmpty()
            assertThat(method.documentation).isNull()
        }

        @Test
        fun `creates method with positional parameters`() {
            val method = MethodDescriptor(
                name = "echo",
                returnType = "void",
                parameters = listOf(
                    ParameterDescriptor("message", "java.lang.String"),
                ),
            )

            assertThat(method.parameters).hasSize(1)
            assertThat(method.parameters[0].name).isEqualTo("message")
        }

        @Test
        fun `creates method with named parameters`() {
            val method = MethodDescriptor(
                name = "sh",
                returnType = "Object",
                namedParameters = listOf(
                    NamedParameterDescriptor("script", "String", required = true),
                    NamedParameterDescriptor("returnStdout", "boolean", defaultValue = "false"),
                    NamedParameterDescriptor("returnStatus", "boolean", defaultValue = "false"),
                ),
            )

            assertThat(method.namedParameters).hasSize(3)
            assertThat(method.namedParameters.map { it.name })
                .containsExactly("script", "returnStdout", "returnStatus")
        }

        @Test
        fun `creates method with documentation`() {
            val method = MethodDescriptor(
                name = "echo",
                returnType = "void",
                documentation = "Print Message",
            )

            assertThat(method.documentation).isEqualTo("Print Message")
        }

        @Test
        fun `equality based on all fields`() {
            val method1 = MethodDescriptor("echo", "void", documentation = "doc")
            val method2 = MethodDescriptor("echo", "void", documentation = "doc")
            val method3 = MethodDescriptor("echo", "void", documentation = "different")

            assertThat(method1).isEqualTo(method2)
            assertThat(method1).isNotEqualTo(method3)
        }
    }

    @Nested
    inner class PropertyDescriptorTest {

        @Test
        fun `creates property with name and type`() {
            val prop = PropertyDescriptor(name = "env", type = "EnvActionImpl")

            assertThat(prop.name).isEqualTo("env")
            assertThat(prop.type).isEqualTo("EnvActionImpl")
            assertThat(prop.documentation).isNull()
        }

        @Test
        fun `creates property with documentation`() {
            val prop = PropertyDescriptor(
                name = "currentBuild",
                type = "RunWrapper",
                documentation = "The current build object",
            )

            assertThat(prop.documentation).isEqualTo("The current build object")
        }

        @Test
        fun `equality based on all fields`() {
            val prop1 = PropertyDescriptor("env", "EnvActionImpl")
            val prop2 = PropertyDescriptor("env", "EnvActionImpl")
            val prop3 = PropertyDescriptor("params", "ParamsVariable")

            assertThat(prop1).isEqualTo(prop2)
            assertThat(prop1).isNotEqualTo(prop3)
        }
    }

    @Nested
    inner class ContextFilterTest {

        @Test
        fun `ScriptScope with empty filetypes`() {
            val scope = ContextFilter.ScriptScope()

            assertThat(scope.filetypes).isEmpty()
        }

        @Test
        fun `ScriptScope with filetypes`() {
            val scope = ContextFilter.ScriptScope(listOf("groovy", "gdsl"))

            assertThat(scope.filetypes).containsExactly("groovy", "gdsl")
        }

        @Test
        fun `ClosureScope without enclosingCall`() {
            val scope = ContextFilter.ClosureScope()

            assertThat(scope.enclosingCall).isNull()
        }

        @Test
        fun `ClosureScope with enclosingCall`() {
            val scope = ContextFilter.ClosureScope(enclosingCall = "node")

            assertThat(scope.enclosingCall).isEqualTo("node")
        }

        @Test
        fun `ClassScope with ctype`() {
            val scope = ContextFilter.ClassScope(ctype = "java.lang.String")

            assertThat(scope.ctype).isEqualTo("java.lang.String")
        }

        @Test
        fun `ContextFilter is sealed class with known subclasses`() {
            val filters: List<ContextFilter> = listOf(
                ContextFilter.ScriptScope(),
                ContextFilter.ClosureScope(),
                ContextFilter.ClassScope("String"),
            )

            // Exhaustive when is possible with sealed classes
            filters.forEach { filter ->
                when (filter) {
                    is ContextFilter.ScriptScope -> assertThat(
                        filter,
                    ).isInstanceOf(ContextFilter.ScriptScope::class.java)
                    is ContextFilter.ClosureScope -> assertThat(
                        filter,
                    ).isInstanceOf(ContextFilter.ClosureScope::class.java)
                    is ContextFilter.ClassScope -> assertThat(filter).isInstanceOf(ContextFilter.ClassScope::class.java)
                }
            }
        }
    }

    @Nested
    inner class GdslParseResultTest {

        @Test
        fun `creates empty result`() {
            val result = GdslParseResult.empty()

            assertThat(result.methods).isEmpty()
            assertThat(result.properties).isEmpty()
            assertThat(result.success).isTrue()
            assertThat(result.error).isNull()
        }

        @Test
        fun `creates result with methods and properties`() {
            val result = GdslParseResult(
                methods = listOf(MethodDescriptor("echo", "void")),
                properties = listOf(PropertyDescriptor("env", "EnvActionImpl")),
            )

            assertThat(result.methods).hasSize(1)
            assertThat(result.properties).hasSize(1)
            assertThat(result.success).isTrue()
        }

        @Test
        fun `creates error result`() {
            val result = GdslParseResult.error("Script compilation failed")

            assertThat(result.methods).isEmpty()
            assertThat(result.properties).isEmpty()
            assertThat(result.success).isFalse()
            assertThat(result.error).isEqualTo("Script compilation failed")
        }

        @Test
        fun `result is immutable`() {
            val methods = mutableListOf(MethodDescriptor("echo", "void"))
            val result = GdslParseResult(methods = methods, properties = emptyList())

            // Modifying the original list should not affect the result
            methods.add(MethodDescriptor("sh", "Object"))

            assertThat(result.methods).hasSize(1)
        }
    }
}
