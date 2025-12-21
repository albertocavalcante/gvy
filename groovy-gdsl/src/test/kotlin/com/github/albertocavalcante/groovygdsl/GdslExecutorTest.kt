package com.github.albertocavalcante.groovygdsl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class GdslExecutorTest {

    private val executor = GdslExecutor()

    @Nested
    inner class ExecuteMethod {

        @Test
        fun `should execute simple GDSL script`() {
            val script = """
                def ctx = context(ctype: "java.lang.String")
                contributor(ctx) {
                    method(name: "foo", type: "void", params: [:])
                }
            """.trimIndent()

            assertDoesNotThrow {
                executor.execute(script, "test.gdsl")
            }
        }

        @Test
        fun `should execute Spock GDSL sample`() {
            val script = this::class.java.getResource("/bundled-gdsls/spock.gdsl")?.readText()
                ?: error("Spock GDSL sample not found")

            assertDoesNotThrow {
                executor.execute(script, "spock.gdsl")
            }
        }

        @Test
        fun `should execute Grails GDSL sample`() {
            val script = this::class.java.getResource("/bundled-gdsls/grails.gdsl")?.readText()
                ?: error("Grails GDSL sample not found")

            assertDoesNotThrow {
                executor.execute(script, "grails.gdsl")
            }
        }
    }

    @Nested
    inner class ExecuteAndCaptureMethod {

        @Test
        fun `captures method from simple contributor`() {
            val script = """
                def ctx = context(ctype: "java.lang.String")
                contributor(ctx) {
                    method(name: "echo", type: "void", doc: "Print message")
                }
            """.trimIndent()

            val result = executor.executeAndCapture(script, "test.gdsl")

            assertThat(result.success).isTrue()
            assertThat(result.methods).hasSize(1)
            assertThat(result.methods[0].name).isEqualTo("echo")
            assertThat(result.methods[0].returnType).isEqualTo("void")
            assertThat(result.methods[0].documentation).isEqualTo("Print message")
        }

        @Test
        fun `captures property from contributor`() {
            val script = """
                def ctx = context(scope: scriptScope())
                contributor(ctx) {
                    property(name: "env", type: "EnvActionImpl")
                }
            """.trimIndent()

            val result = executor.executeAndCapture(script, "test.gdsl")

            assertThat(result.success).isTrue()
            assertThat(result.properties).hasSize(1)
            assertThat(result.properties[0].name).isEqualTo("env")
            assertThat(result.properties[0].type).isEqualTo("EnvActionImpl")
        }

        @Test
        fun `captures method with positional parameters`() {
            val script = """
                contributor([context()]) {
                    method(name: "echo", type: "void", params: [message: 'java.lang.String'])
                }
            """.trimIndent()

            val result = executor.executeAndCapture(script, "test.gdsl")

            assertThat(result.methods[0].parameters).hasSize(1)
            assertThat(result.methods[0].parameters[0].name).isEqualTo("message")
            assertThat(result.methods[0].parameters[0].type).isEqualTo("java.lang.String")
        }

        @Test
        fun `captures method with named parameters`() {
            val script = """
                contributor([context()]) {
                    method(name: "sh", type: "Object", namedParams: [
                        parameter(name: 'script', type: 'String'),
                        parameter(name: 'returnStdout', type: 'boolean'),
                    ])
                }
            """.trimIndent()

            val result = executor.executeAndCapture(script, "test.gdsl")

            assertThat(result.methods[0].namedParameters).hasSize(2)
            assertThat(result.methods[0].namedParameters[0].name).isEqualTo("script")
            assertThat(result.methods[0].namedParameters[1].name).isEqualTo("returnStdout")
        }

        @Test
        fun `captures multiple methods from single contributor`() {
            val script = """
                contributor([context()]) {
                    method(name: "echo", type: "void")
                    method(name: "sh", type: "Object")
                    method(name: "bat", type: "Object")
                }
            """.trimIndent()

            val result = executor.executeAndCapture(script, "test.gdsl")

            assertThat(result.methods).hasSize(3)
            assertThat(result.methods.map { it.name }).containsExactly("echo", "sh", "bat")
        }

        @Test
        fun `captures methods and properties from multiple contributors`() {
            val script = """
                def scriptCtx = context(scope: scriptScope())
                contributor(scriptCtx) {
                    method(name: "echo", type: "void")
                    property(name: "env", type: "EnvActionImpl")
                }

                def nodeCtx = context(scope: closureScope())
                contributor(nodeCtx) {
                    method(name: "sh", type: "Object")
                    property(name: "currentBuild", type: "RunWrapper")
                }
            """.trimIndent()

            val result = executor.executeAndCapture(script, "test.gdsl")

            assertThat(result.methods).hasSize(2)
            assertThat(result.properties).hasSize(2)
            assertThat(result.methods.map { it.name }).containsExactlyInAnyOrder("echo", "sh")
            assertThat(result.properties.map { it.name }).containsExactlyInAnyOrder("env", "currentBuild")
        }

        @Test
        fun `returns error result for invalid script`() {
            val script = """
                this is not valid groovy syntax {{{
            """.trimIndent()

            val result = executor.executeAndCapture(script, "invalid.gdsl")

            assertThat(result.success).isFalse()
            assertThat(result.error).isNotNull()
            assertThat(result.methods).isEmpty()
            assertThat(result.properties).isEmpty()
        }

        @Test
        fun `returns error result for runtime exception`() {
            val script = """
                throw new RuntimeException("Test error")
            """.trimIndent()

            val result = executor.executeAndCapture(script, "error.gdsl")

            assertThat(result.success).isFalse()
            assertThat(result.error).contains("Test error")
        }

        @Test
        fun `handles empty contributor`() {
            val script = """
                contributor([context()]) {
                    // empty
                }
            """.trimIndent()

            val result = executor.executeAndCapture(script, "empty.gdsl")

            assertThat(result.success).isTrue()
            assertThat(result.methods).isEmpty()
            assertThat(result.properties).isEmpty()
        }

        @Test
        fun `handles script with no contributors`() {
            val script = """
                def x = 1 + 2
            """.trimIndent()

            val result = executor.executeAndCapture(script, "no-contributor.gdsl")

            assertThat(result.success).isTrue()
            assertThat(result.methods).isEmpty()
            assertThat(result.properties).isEmpty()
        }
    }

    @Nested
    inner class JenkinsGdslParsing {

        @Test
        fun `parses Jenkins-style GDSL with script scope`() {
            val script = """
                //The global script scope
                def ctx = context(scope: scriptScope())
                contributor(ctx) {
                    method(name: 'echo', type: 'Object', params: [message:'java.lang.String'], doc: 'Print Message')
                    method(name: 'error', type: 'Object', params: [message:'java.lang.String'], doc: 'Error signal')
                    property(name: 'env', type: 'org.jenkinsci.plugins.workflow.cps.EnvActionImpl')
                    property(name: 'params', type: 'org.jenkinsci.plugins.workflow.cps.ParamsVariable')
                }
            """.trimIndent()

            val result = executor.executeAndCapture(script, "jenkins.gdsl")

            assertThat(result.success).isTrue()
            assertThat(result.methods).hasSize(2)
            assertThat(result.properties).hasSize(2)
            assertThat(result.methods.map { it.name }).containsExactly("echo", "error")
            assertThat(result.properties.map { it.name }).containsExactly("env", "params")
        }

        @Test
        fun `parses Jenkins-style GDSL with closure scope`() {
            val script = """
                //Steps that require a node context
                def nodeCtx = context(scope: closureScope())
                contributor(nodeCtx) {
                    method(name: 'sh', type: 'Object', params: [script:'java.lang.String'], doc: 'Shell Script')
                    method(name: 'bat', type: 'Object', params: [script:'java.lang.String'], doc: 'Windows Batch Script')
                }
            """.trimIndent()

            val result = executor.executeAndCapture(script, "jenkins-node.gdsl")

            assertThat(result.success).isTrue()
            assertThat(result.methods).hasSize(2)
            assertThat(result.methods.map { it.name }).containsExactly("sh", "bat")
        }
    }
}
