// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.gdsl

import com.github.albertocavalcante.gvy.gdsl.GdslExecutor
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

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

            assertThat(
                assertNotNull(metadata.typedCatalogue).symbols
                    .filter { it.kind == SymbolKind.EXECUTABLE_STEP }.map { it.name }.toSet(),
            ).containsAll(
                listOf("echo", "error", "isUnix", "mail", "parallel", "timeout", "retry", "sleep", "stage", "input"),
            )
        }

        @Test
        fun `extracts node-context steps`() {
            val result = executor.executeAndCapture(sampleGdsl, "sample-gdsl.groovy")
            val metadata = adapter.convert(result)

            assertThat(
                assertNotNull(metadata.typedCatalogue).symbols
                    .filter { it.kind == SymbolKind.EXECUTABLE_STEP }.map { it.name }.toSet(),
            ).containsAll(
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

            assertThat(metadata.getStep("sh")).isNull()
            val candidates = assertNotNull(metadata.typedCatalogue).candidates(SymbolKind.EXECUTABLE_STEP, "sh")
            assertThat(candidates).hasSize(2)
            assertThat(candidates.map { it.id }.distinct()).hasSize(2)
            assertThat(candidates.map { it.documentation }).containsOnly("Shell Script")
            assertThat(candidates.map { it.contexts }).containsOnly(listOf("node"))
            val positional = assertNotNull(candidates.singleOrNull { it.positional == listOf("script") })
            assertThat(positional.parameters.map { it.name }).containsExactly("script")
            val named = assertNotNull(candidates.singleOrNull { it.positional.isEmpty() })
            assertThat(named.parameters.map { it.name })
                .containsExactly("script", "encoding", "label", "returnStatus", "returnStdout")
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

            assertThat(metadata.steps.keys).containsExactly("globalStep")
            assertThat(metadata.getStep("nodeStep")).isNull()
            val node = assertNotNull(
                metadata.typedCatalogue,
            ).candidates(SymbolKind.EXECUTABLE_STEP, "nodeStep").single()
            assertThat(node.gdslContext).isEqualTo(
                kotlinx.serialization.json.Json.parseToJsonElement("""{"scope":{"type":"closure"}}"""),
            )
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

    @Test
    fun `default authoring snippets produce global Jenkins metadata`() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/editors/code/client/src/test/fixtures/gdsl-snippet-defaults.gdsl"),
        ).bufferedReader().use { it.readText() }
        val scripts = fixture.split("// --- named method ---")
        assertThat(scripts).hasSize(2)

        val positional = executor.executeAndCapture(scripts[0])
        assertThat(positional.success).withFailMessage("Parse failed: ${positional.error}").isTrue()
        assertThat(positional.methods.single().parameters.single().name).isEqualTo("arg")
        assertThat(positional.properties.single().type).isEqualTo("java.lang.Object")
        val positionalMetadata = adapter.convert(positional)
        assertThat(positionalMetadata.getStep("myMethod")?.parameters?.get("arg")?.type).isEqualTo("String")
        assertThat(positionalMetadata.getGlobalVariable("myProperty")?.type).isEqualTo("Object")

        val named = executor.executeAndCapture(scripts[1])
        assertThat(named.success).withFailMessage("Parse failed: ${named.error}").isTrue()
        assertThat(named.methods.map { it.namedParameters.single().name }).containsExactly("myParam", "myParam")
        val namedMetadata = adapter.convert(named)
        assertThat(namedMetadata.getStep("myMethod")?.parameters?.get("myParam")?.type).isEqualTo("String")
        assertThat(namedMetadata.getStep("parameterExample")?.parameters?.get("myParam")?.type).isEqualTo("String")
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
