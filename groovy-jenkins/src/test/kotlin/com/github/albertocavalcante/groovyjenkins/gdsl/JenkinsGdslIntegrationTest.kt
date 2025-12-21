package com.github.albertocavalcante.groovyjenkins.gdsl

import com.github.albertocavalcante.groovygdsl.GdslExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Integration tests for the execution-based GDSL parsing pipeline.
 *
 * These tests verify the full workflow:
 * 1. GdslExecutor parses GDSL script
 * 2. JenkinsGdslAdapter converts to Jenkins metadata
 * 3. Metadata contains expected steps and global variables
 */
class JenkinsGdslIntegrationTest {

    private lateinit var executor: GdslExecutor
    private lateinit var adapter: JenkinsGdslAdapter

    @BeforeEach
    fun setup() {
        executor = GdslExecutor()
        adapter = JenkinsGdslAdapter()
    }

    @Nested
    inner class SampleGdslParsing {

        private val sampleGdsl: String by lazy {
            this::class.java.getResourceAsStream("/sample-gdsl.groovy")?.bufferedReader()?.readText()
                ?: error("sample-gdsl.groovy not found in test resources")
        }

        @Test
        fun `parses sample Jenkins GDSL successfully`() {
            val result = executor.executeAndCapture(sampleGdsl, "sample-gdsl.groovy")

            assertThat(result.success)
                .withFailMessage("Parse failed: ${result.error}")
                .isTrue()
        }

        @Test
        fun `extracts core pipeline steps`() {
            val result = executor.executeAndCapture(sampleGdsl, "sample-gdsl.groovy")
            val metadata = adapter.convert(result)

            assertThat(metadata.steps.keys).containsAll(
                listOf("echo", "error", "isUnix", "mail", "parallel", "timeout", "retry", "sleep", "stage", "input"),
            )
        }

        @Test
        fun `extracts node-context steps`() {
            val result = executor.executeAndCapture(sampleGdsl, "sample-gdsl.groovy")
            val metadata = adapter.convert(result)

            assertThat(metadata.steps.keys).containsAll(
                listOf(
                    "sh", "bat", "powershell", "pwsh", "dir",
                    "deleteDir", "pwd", "fileExists", "readFile", "writeFile",
                ),
            )
        }

        @Test
        fun `extracts global variables`() {
            val result = executor.executeAndCapture(sampleGdsl, "sample-gdsl.groovy")
            val metadata = adapter.convert(result)

            assertThat(metadata.globalVariables.keys).containsExactlyInAnyOrder(
                "env",
                "params",
                "currentBuild",
                "scm",
            )
        }

        @Test
        fun `echo step has correct metadata`() {
            val result = executor.executeAndCapture(sampleGdsl, "sample-gdsl.groovy")
            val metadata = adapter.convert(result)

            val echo = metadata.getStep("echo")
            assertThat(echo).isNotNull
            assertThat(echo!!.documentation).isEqualTo("Print Message")
            assertThat(echo.parameters["message"]).isNotNull
            assertThat(echo.parameters["message"]!!.type).isEqualTo("String")
        }

        @Test
        fun `sh step has named parameters with returnStdout`() {
            val result = executor.executeAndCapture(sampleGdsl, "sample-gdsl.groovy")
            val metadata = adapter.convert(result)

            val sh = metadata.getStep("sh")
            assertThat(sh).isNotNull
            assertThat(sh!!.documentation).isEqualTo("Shell Script")
            assertThat(sh.parameters.keys).contains("script", "returnStdout", "returnStatus")
        }

        @Test
        fun `env global variable has correct type`() {
            val result = executor.executeAndCapture(sampleGdsl, "sample-gdsl.groovy")
            val metadata = adapter.convert(result)

            val env = metadata.getGlobalVariable("env")
            assertThat(env).isNotNull
            assertThat(env!!.type).isEqualTo("org.jenkinsci.plugins.workflow.cps.EnvActionImpl")
        }
    }

    @Nested
    inner class MinimalGdslParsing {

        @Test
        fun `parses minimal GDSL with single step`() {
            val gdsl = """
                def ctx = context(scope: scriptScope())
                contributor(ctx) {
                    method(name: 'customStep', type: 'void', doc: 'Custom step')
                }
            """.trimIndent()

            val result = executor.executeAndCapture(gdsl)
            val metadata = adapter.convert(result)

            assertThat(metadata.steps).hasSize(1)
            assertThat(metadata.steps["customStep"]).isNotNull
        }

        @Test
        fun `parses GDSL with multiple contributors`() {
            val gdsl = """
                def scriptCtx = context(scope: scriptScope())
                contributor(scriptCtx) {
                    method(name: 'globalStep', type: 'void')
                    property(name: 'globalVar', type: 'String')
                }

                def nodeCtx = context(scope: closureScope())
                contributor(nodeCtx) {
                    method(name: 'nodeStep', type: 'void')
                }
            """.trimIndent()

            val result = executor.executeAndCapture(gdsl)
            val metadata = adapter.convert(result)

            assertThat(metadata.steps.keys).containsExactlyInAnyOrder("globalStep", "nodeStep")
            assertThat(metadata.globalVariables.keys).containsExactly("globalVar")
        }

        @Test
        fun `handles GDSL with complex parameter types`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'withCredentials', type: 'Object', params: [bindings: 'java.util.List', body: Closure])
                }
            """.trimIndent()

            val result = executor.executeAndCapture(gdsl)
            val metadata = adapter.convert(result)

            val step = metadata.getStep("withCredentials")
            assertThat(step).isNotNull
            assertThat(step!!.parameters.keys).containsExactlyInAnyOrder("bindings", "body")
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `handles invalid GDSL gracefully`() {
            val gdsl = "this is not valid GDSL syntax {{{"

            val result = executor.executeAndCapture(gdsl)

            assertThat(result.success).isFalse()
            assertThat(result.error).isNotNull()
        }

        @Test
        fun `adapter returns empty metadata for failed parse`() {
            val gdsl = "invalid syntax"

            val result = executor.executeAndCapture(gdsl)
            val metadata = adapter.convert(result)

            assertThat(metadata.steps).isEmpty()
            assertThat(metadata.globalVariables).isEmpty()
        }
    }
}
