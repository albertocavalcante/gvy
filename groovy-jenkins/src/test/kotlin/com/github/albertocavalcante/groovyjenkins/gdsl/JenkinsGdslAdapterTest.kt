package com.github.albertocavalcante.groovyjenkins.gdsl

import com.github.albertocavalcante.groovygdsl.model.GdslParseResult
import com.github.albertocavalcante.groovygdsl.model.MethodDescriptor
import com.github.albertocavalcante.groovygdsl.model.NamedParameterDescriptor
import com.github.albertocavalcante.groovygdsl.model.ParameterDescriptor
import com.github.albertocavalcante.groovygdsl.model.PropertyDescriptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * TDD tests for JenkinsGdslAdapter.
 *
 * The adapter converts GdslParseResult from the groovy-gdsl module
 * to Jenkins-specific metadata types.
 */
class JenkinsGdslAdapterTest {

    private val adapter = JenkinsGdslAdapter()

    @Nested
    inner class ConvertMethodToStep {

        @Test
        fun `converts method to Jenkins step metadata`() {
            val method = MethodDescriptor(
                name = "echo",
                returnType = "void",
                documentation = "Print Message",
            )

            val step = adapter.convertMethod(method)

            assertThat(step.name).isEqualTo("echo")
            assertThat(step.documentation).isEqualTo("Print Message")
            assertThat(step.plugin).isEqualTo("gdsl-extracted")
        }

        @Test
        fun `converts method with positional parameters`() {
            val method = MethodDescriptor(
                name = "echo",
                returnType = "void",
                parameters = listOf(
                    ParameterDescriptor("message", "java.lang.String"),
                ),
            )

            val step = adapter.convertMethod(method)

            assertThat(step.parameters).hasSize(1)
            assertThat(step.parameters["message"]).isNotNull
            // Type should be simplified from java.lang.String to String
            assertThat(step.parameters["message"]!!.type).isEqualTo("String")
        }

        @Test
        fun `converts method with named parameters`() {
            val method = MethodDescriptor(
                name = "sh",
                returnType = "Object",
                namedParameters = listOf(
                    NamedParameterDescriptor("script", "String", required = true),
                    NamedParameterDescriptor("returnStdout", "boolean", defaultValue = "false"),
                    NamedParameterDescriptor("returnStatus", "boolean", defaultValue = "false"),
                ),
            )

            val step = adapter.convertMethod(method)

            assertThat(step.parameters).hasSize(3)
            assertThat(step.parameters["script"]!!.required).isTrue()
            assertThat(step.parameters["returnStdout"]!!.default).isEqualTo("false")
        }

        @Test
        fun `prefers named parameters over positional when both present`() {
            val method = MethodDescriptor(
                name = "sh",
                returnType = "Object",
                parameters = listOf(ParameterDescriptor("script", "String")),
                namedParameters = listOf(
                    NamedParameterDescriptor("script", "String", required = true, documentation = "Script to run"),
                ),
            )

            val step = adapter.convertMethod(method)

            // Named params have more metadata, should be preferred
            assertThat(step.parameters["script"]!!.documentation).isEqualTo("Script to run")
            assertThat(step.parameters["script"]!!.required).isTrue()
        }

        @Test
        fun `simplifies Java types to simple names`() {
            val method = MethodDescriptor(
                name = "test",
                returnType = "void",
                parameters = listOf(
                    ParameterDescriptor("text", "java.lang.String"),
                    ParameterDescriptor("flag", "java.lang.Boolean"),
                    ParameterDescriptor("body", "groovy.lang.Closure"),
                ),
            )

            val step = adapter.convertMethod(method)

            assertThat(step.parameters["text"]!!.type).isEqualTo("String")
            assertThat(step.parameters["flag"]!!.type).isEqualTo("Boolean")
            assertThat(step.parameters["body"]!!.type).isEqualTo("Closure")
        }
    }

    @Nested
    inner class ConvertPropertyToGlobal {

        @Test
        fun `converts property to global variable metadata`() {
            val property = PropertyDescriptor(
                name = "env",
                type = "org.jenkinsci.plugins.workflow.cps.EnvActionImpl",
            )

            val global = adapter.convertProperty(property)

            assertThat(global.name).isEqualTo("env")
            assertThat(global.type).isEqualTo("org.jenkinsci.plugins.workflow.cps.EnvActionImpl")
        }

        @Test
        fun `converts property with documentation`() {
            val property = PropertyDescriptor(
                name = "currentBuild",
                type = "RunWrapper",
                documentation = "The current build",
            )

            val global = adapter.convertProperty(property)

            assertThat(global.documentation).isEqualTo("The current build")
        }
    }

    @Nested
    inner class ConvertResult {

        @Test
        fun `converts full GdslParseResult to Jenkins metadata`() {
            val result = GdslParseResult(
                methods = listOf(
                    MethodDescriptor("echo", "void"),
                    MethodDescriptor("sh", "Object"),
                ),
                properties = listOf(
                    PropertyDescriptor("env", "EnvActionImpl"),
                    PropertyDescriptor("params", "ParamsVariable"),
                ),
            )

            val metadata = adapter.convert(result)

            assertThat(metadata.steps).hasSize(2)
            assertThat(metadata.steps.keys).containsExactlyInAnyOrder("echo", "sh")
            assertThat(metadata.globalVariables).hasSize(2)
            assertThat(metadata.globalVariables.keys).containsExactlyInAnyOrder("env", "params")
        }

        @Test
        fun `returns empty metadata for failed parse result`() {
            val result = GdslParseResult.error("Parse failed")

            val metadata = adapter.convert(result)

            assertThat(metadata.steps).isEmpty()
            assertThat(metadata.globalVariables).isEmpty()
        }

        @Test
        fun `returns empty metadata for empty parse result`() {
            val result = GdslParseResult.empty()

            val metadata = adapter.convert(result)

            assertThat(metadata.steps).isEmpty()
            assertThat(metadata.globalVariables).isEmpty()
        }

        @Test
        fun `handles duplicate step names by keeping last`() {
            val result = GdslParseResult(
                methods = listOf(
                    MethodDescriptor("echo", "void", documentation = "First"),
                    MethodDescriptor("echo", "void", documentation = "Second"),
                ),
                properties = emptyList(),
            )

            val metadata = adapter.convert(result)

            assertThat(metadata.steps).hasSize(1)
            assertThat(metadata.steps["echo"]!!.documentation).isEqualTo("Second")
        }
    }

    @Nested
    inner class PluginExtraction {

        @Test
        fun `uses default plugin name for GDSL-extracted steps`() {
            val method = MethodDescriptor("custom", "void")

            val step = adapter.convertMethod(method)

            assertThat(step.plugin).isEqualTo("gdsl-extracted")
        }

        @Test
        fun `can specify custom plugin source`() {
            val method = MethodDescriptor("custom", "void")

            val step = adapter.convertMethod(method, pluginSource = "user-gdsl")

            assertThat(step.plugin).isEqualTo("user-gdsl")
        }
    }
}
