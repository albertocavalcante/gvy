package com.github.albertocavalcante.groovygdsl

import com.github.albertocavalcante.groovygdsl.model.NamedParameterDescriptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * TDD tests for GdslContributor.
 *
 * The contributor is the delegate for GDSL contributor closures,
 * capturing method() and property() calls as descriptors.
 */
class GdslContributorTest {

    private lateinit var contributor: GdslContributor

    @BeforeEach
    fun setup() {
        contributor = GdslContributor(GdslContext(emptyMap()))
    }

    @Nested
    inner class MethodCapturing {

        @Test
        fun `captures method with name and return type`() {
            contributor.method(mapOf("name" to "echo", "type" to "void"))

            assertThat(contributor.methods).hasSize(1)
            assertThat(contributor.methods[0].name).isEqualTo("echo")
            assertThat(contributor.methods[0].returnType).isEqualTo("void")
        }

        @Test
        fun `captures method with default return type when not specified`() {
            contributor.method(mapOf("name" to "echo"))

            assertThat(contributor.methods[0].returnType).isEqualTo("java.lang.Object")
        }

        @Test
        fun `ignores method call without name`() {
            contributor.method(mapOf("type" to "void"))

            assertThat(contributor.methods).isEmpty()
        }

        @Test
        fun `captures method with documentation`() {
            contributor.method(
                mapOf(
                    "name" to "echo",
                    "type" to "void",
                    "doc" to "Print Message",
                ),
            )

            assertThat(contributor.methods[0].documentation).isEqualTo("Print Message")
        }

        @Test
        fun `captures method with positional parameters`() {
            contributor.method(
                mapOf(
                    "name" to "echo",
                    "type" to "void",
                    "params" to mapOf("message" to "java.lang.String"),
                ),
            )

            val method = contributor.methods[0]
            assertThat(method.parameters).hasSize(1)
            assertThat(method.parameters[0].name).isEqualTo("message")
            assertThat(method.parameters[0].type).isEqualTo("java.lang.String")
        }

        @Test
        fun `captures method with multiple positional parameters`() {
            contributor.method(
                mapOf(
                    "name" to "retry",
                    "type" to "Object",
                    "params" to mapOf(
                        "count" to "int",
                        "body" to "Closure",
                    ),
                ),
            )

            val method = contributor.methods[0]
            assertThat(method.parameters).hasSize(2)
            assertThat(method.parameters.map { it.name }).containsExactlyInAnyOrder("count", "body")
        }

        @Test
        fun `captures method with named parameters from list`() {
            val namedParams = listOf(
                contributor.parameter(mapOf("name" to "script", "type" to "String")),
                contributor.parameter(mapOf("name" to "returnStdout", "type" to "boolean")),
            )

            contributor.method(
                mapOf(
                    "name" to "sh",
                    "type" to "Object",
                    "namedParams" to namedParams,
                ),
            )

            val method = contributor.methods[0]
            assertThat(method.namedParameters).hasSize(2)
            assertThat(method.namedParameters[0].name).isEqualTo("script")
            assertThat(method.namedParameters[1].name).isEqualTo("returnStdout")
        }

        @Test
        fun `captures multiple methods`() {
            contributor.method(mapOf("name" to "echo", "type" to "void"))
            contributor.method(mapOf("name" to "sh", "type" to "Object"))
            contributor.method(mapOf("name" to "bat", "type" to "Object"))

            assertThat(contributor.methods).hasSize(3)
            assertThat(contributor.methods.map { it.name })
                .containsExactly("echo", "sh", "bat")
        }
    }

    @Nested
    inner class PropertyCapturing {

        @Test
        fun `captures property with name and type`() {
            contributor.property(mapOf("name" to "env", "type" to "EnvActionImpl"))

            assertThat(contributor.properties).hasSize(1)
            assertThat(contributor.properties[0].name).isEqualTo("env")
            assertThat(contributor.properties[0].type).isEqualTo("EnvActionImpl")
        }

        @Test
        fun `captures property with default type when not specified`() {
            contributor.property(mapOf("name" to "env"))

            assertThat(contributor.properties[0].type).isEqualTo("java.lang.Object")
        }

        @Test
        fun `ignores property call without name`() {
            contributor.property(mapOf("type" to "String"))

            assertThat(contributor.properties).isEmpty()
        }

        @Test
        fun `captures property with documentation`() {
            contributor.property(
                mapOf(
                    "name" to "currentBuild",
                    "type" to "RunWrapper",
                    "doc" to "The current build object",
                ),
            )

            assertThat(contributor.properties[0].documentation).isEqualTo("The current build object")
        }

        @Test
        fun `captures multiple properties`() {
            contributor.property(mapOf("name" to "env", "type" to "EnvActionImpl"))
            contributor.property(mapOf("name" to "params", "type" to "ParamsVariable"))
            contributor.property(mapOf("name" to "currentBuild", "type" to "RunWrapper"))

            assertThat(contributor.properties).hasSize(3)
            assertThat(contributor.properties.map { it.name })
                .containsExactly("env", "params", "currentBuild")
        }
    }

    @Nested
    inner class ParameterCreation {

        @Test
        fun `creates named parameter descriptor`() {
            val param = contributor.parameter(mapOf("name" to "script", "type" to "String"))

            assertThat(param).isInstanceOf(NamedParameterDescriptor::class.java)
            assertThat(param.name).isEqualTo("script")
            assertThat(param.type).isEqualTo("String")
        }

        @Test
        fun `creates parameter with documentation`() {
            val param = contributor.parameter(
                mapOf(
                    "name" to "script",
                    "type" to "String",
                    "doc" to "The shell script to execute",
                ),
            )

            assertThat(param.documentation).isEqualTo("The shell script to execute")
        }

        @Test
        fun `creates parameter with default type when not specified`() {
            val param = contributor.parameter(mapOf("name" to "value"))

            assertThat(param.type).isEqualTo("java.lang.Object")
        }

        @Test
        fun `creates parameter with empty name when not specified`() {
            val param = contributor.parameter(mapOf("type" to "String"))

            assertThat(param.name).isEmpty()
        }
    }

    @Nested
    inner class TypeConversion {

        @Test
        fun `converts Class type to string`() {
            contributor.method(
                mapOf(
                    "name" to "test",
                    "type" to String::class.java,
                ),
            )

            assertThat(contributor.methods[0].returnType).isEqualTo("java.lang.String")
        }

        @Test
        fun `converts Closure type to groovy Closure`() {
            contributor.method(
                mapOf(
                    "name" to "test",
                    "params" to mapOf("body" to groovy.lang.Closure::class.java),
                ),
            )

            assertThat(contributor.methods[0].parameters[0].type).isEqualTo("groovy.lang.Closure")
        }

        @Test
        fun `handles null type gracefully`() {
            contributor.method(mapOf("name" to "test", "type" to null))

            assertThat(contributor.methods[0].returnType).isEqualTo("java.lang.Object")
        }
    }

    @Nested
    inner class ImmutabilityTest {

        @Test
        fun `methods list is immutable snapshot`() {
            contributor.method(mapOf("name" to "echo", "type" to "void"))
            val methods1 = contributor.methods

            contributor.method(mapOf("name" to "sh", "type" to "Object"))
            val methods2 = contributor.methods

            // First snapshot should not change
            assertThat(methods1).hasSize(1)
            assertThat(methods2).hasSize(2)
        }

        @Test
        fun `properties list is immutable snapshot`() {
            contributor.property(mapOf("name" to "env", "type" to "EnvActionImpl"))
            val props1 = contributor.properties

            contributor.property(mapOf("name" to "params", "type" to "ParamsVariable"))
            val props2 = contributor.properties

            // First snapshot should not change
            assertThat(props1).hasSize(1)
            assertThat(props2).hasSize(2)
        }
    }
}
