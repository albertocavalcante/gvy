package com.github.albertocavalcante.groovyjenkins.extractor

import com.github.albertocavalcante.groovyjenkins.metadata.extracted.PluginInfo
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class GdslToJsonTest {

    @Nested
    inner class `Type Simplification` {

        @Test
        fun `simplifies java lang types`() {
            assertThat(simplifyType("java.lang.String")).isEqualTo("String")
            assertThat(simplifyType("java.lang.Object")).isEqualTo("Object")
            assertThat(simplifyType("java.lang.Boolean")).isEqualTo("Boolean")
        }

        @Test
        fun `preserves simple types`() {
            assertThat(simplifyType("String")).isEqualTo("String")
            assertThat(simplifyType("boolean")).isEqualTo("boolean")
            assertThat(simplifyType("int")).isEqualTo("int")
        }

        @Test
        fun `preserves custom types`() {
            assertThat(simplifyType("org.jenkinsci.plugins.workflow.cps.EnvActionImpl"))
                .isEqualTo("org.jenkinsci.plugins.workflow.cps.EnvActionImpl")
            assertThat(simplifyType("hudson.model.Run"))
                .isEqualTo("hudson.model.Run")
        }

        @Test
        fun `handles void type`() {
            assertThat(simplifyType("void")).isEqualTo("void")
        }
    }

    @Nested
    inner class `GDSL to ExtractedStep Conversion` {

        @Test
        fun `converts simple method to ExtractedStep`() {
            val gdsl = """
                def ctx = context(scope: scriptScope())
                contributor(ctx) {
                    method(name: 'echo', type: 'void', params: [message:'java.lang.String'], doc: 'Print Message')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo(
                    id = "workflow-basic-steps",
                    version = "1058.v1",
                    displayName = "Pipeline: Basic Steps",
                ),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "test-sha256",
                    pluginsManifestSha256 = "manifest-sha256",
                ),
            )

            assertThat(metadata.steps).hasSize(1)
            val echoStep = metadata.steps["echo"]
            assertThat(echoStep).isNotNull
            assertThat(echoStep?.scope).isEqualTo(StepScope.GLOBAL)
            assertThat(echoStep?.positionalParams).containsExactly("message")
            assertThat(echoStep?.namedParams?.get("message")?.type).isEqualTo("String")
            assertThat(echoStep?.documentation).isEqualTo("Print Message")
            assertThat(echoStep?.returnType).isEqualTo("void")
        }

        @Test
        fun `converts method with named parameters`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'sh', type: 'Object', namedParams: [
                        parameter(name: 'script', type: 'java.lang.String'),
                        parameter(name: 'returnStdout', type: 'boolean'),
                    ])
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            val shStep = metadata.steps["sh"]
            assertThat(shStep).isNotNull
            assertThat(shStep?.positionalParams).isEmpty()
            assertThat(shStep?.namedParams).hasSize(2)
            assertThat(shStep?.namedParams?.get("script")?.type).isEqualTo("String")
            assertThat(shStep?.namedParams?.get("returnStdout")?.type).isEqualTo("boolean")
        }

        @Test
        fun `handles methods without documentation`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'error', type: 'void', params: [message:'String'])
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            val errorStep = metadata.steps["error"]
            assertThat(errorStep?.documentation).isNull()
        }
    }

    @Nested
    inner class `Scope Detection` {

        @Test
        fun `detects GLOBAL scope for scriptScope context`() {
            val gdsl = """
                def ctx = context(scope: scriptScope())
                contributor(ctx) {
                    method(name: 'echo', type: 'void')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            assertThat(metadata.steps["echo"]?.scope).isEqualTo(StepScope.GLOBAL)
        }

        @Test
        fun `detects NODE scope for closureScope context`() {
            val gdsl = """
                def ctx = context(scope: closureScope())
                contributor(ctx) {
                    method(name: 'sh', type: 'Object')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            // NOTE: Heuristic - closureScope could be node or stage context
            // For now, defaulting to NODE as it's more common
            // TODO: Parse GDSL comments or context hints for deterministic scope
            assertThat(metadata.steps["sh"]?.scope).isEqualTo(StepScope.NODE)
        }

        @Test
        fun `defaults to GLOBAL scope for unknown context`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'step', type: 'void')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            assertThat(metadata.steps["step"]?.scope).isEqualTo(StepScope.GLOBAL)
        }
    }

    @Nested
    inner class `Global Variables` {

        @Test
        fun `extracts global variables from properties`() {
            val gdsl = """
                def ctx = context(scope: scriptScope())
                contributor(ctx) {
                    property(name: 'env', type: 'org.jenkinsci.plugins.workflow.cps.EnvActionImpl', doc: 'Environment variables')
                    property(name: 'params', type: 'org.jenkinsci.plugins.workflow.cps.ParamsVariable')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            assertThat(metadata.globalVariables).hasSize(2)
            assertThat(metadata.globalVariables["env"]?.type)
                .isEqualTo("org.jenkinsci.plugins.workflow.cps.EnvActionImpl")
            assertThat(metadata.globalVariables["env"]?.documentation)
                .isEqualTo("Environment variables")
            assertThat(metadata.globalVariables["params"]?.type)
                .isEqualTo("org.jenkinsci.plugins.workflow.cps.ParamsVariable")
        }
    }

    @Nested
    inner class `Plugin Metadata Structure` {

        @Test
        fun `includes plugin info`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'test', type: 'void')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo(
                    id = "workflow-basic-steps",
                    version = "1058.vcb_fc1e3a_21a_9",
                    displayName = "Pipeline: Basic Steps",
                ),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "test-sha",
                    pluginsManifestSha256 = "manifest-sha",
                ),
            )

            assertThat(metadata.plugin.id).isEqualTo("workflow-basic-steps")
            assertThat(metadata.plugin.version).isEqualTo("1058.vcb_fc1e3a_21a_9")
            assertThat(metadata.plugin.displayName).isEqualTo("Pipeline: Basic Steps")
        }

        @Test
        fun `includes extraction info`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'test', type: 'void')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "abc123",
                    pluginsManifestSha256 = "def456",
                ),
            )

            assertThat(metadata.extraction.jenkinsVersion).isEqualTo("2.426.3")
            assertThat(metadata.extraction.gdslSha256).isEqualTo("abc123")
            assertThat(metadata.extraction.pluginsManifestSha256).isEqualTo("def456")
            assertThat(metadata.extraction.extractorVersion).isEqualTo("1.0.0")
            assertThat(metadata.extraction.extractedAt).isNotEmpty()
        }

        @Test
        fun `uses provided extractedAt`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'test', type: 'void')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "abc123",
                    pluginsManifestSha256 = "def456",
                    extractedAt = "2020-01-01T00:00:00Z",
                ),
            )

            assertThat(metadata.extraction.extractedAt).isEqualTo("2020-01-01T00:00:00Z")
        }

        @Test
        fun `includes schema URL`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'test', type: 'void')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            assertThat(metadata.schema)
                .isEqualTo("https://groovy-lsp.dev/schemas/jenkins-plugin-metadata-v1.json")
        }
    }

    @Nested
    inner class `JSON Serialization` {

        @Test
        fun `serializes to pretty JSON`(@TempDir tempDir: Path) {
            val gdsl = """
                def ctx = context(scope: scriptScope())
                contributor(ctx) {
                    method(name: 'echo', type: 'void', params: [message:'String'], doc: 'Print message')
                }
            """.trimIndent()

            val outputFile = tempDir.resolve("workflow-basic-steps.json")

            writePluginMetadataJson(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("workflow-basic-steps", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
                outputFile = outputFile,
            )

            val jsonContent = outputFile.readText()

            // Verify it's pretty-printed (has newlines and indentation)
            assertThat(jsonContent).contains("\n")
            assertThat(jsonContent).contains("  ") // indentation
            assertThat(jsonContent).contains("\"echo\"")
            assertThat(jsonContent).contains("\"scope\": \"global\"")
        }

        @Test
        fun `writes valid JSON structure`(@TempDir tempDir: Path) {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'sh', type: 'Object', namedParams: [
                        parameter(name: 'script', type: 'String'),
                    ])
                }
            """.trimIndent()

            val outputFile = tempDir.resolve("test-plugin.json")

            writePluginMetadataJson(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test-plugin", "1.0", "Test Plugin"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha256",
                    pluginsManifestSha256 = "manifest-sha",
                ),
                outputFile = outputFile,
            )

            // Should be able to read it back without errors
            val jsonContent = outputFile.readText()
            assertThat(jsonContent).isNotEmpty()
            assertThat(jsonContent).startsWith("{")
            assertThat(jsonContent).endsWith("}\n")
        }
    }

    @Nested
    inner class `Multiple Steps` {

        @Test
        fun `handles multiple steps from single contributor`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'echo', type: 'void')
                    method(name: 'error', type: 'void')
                    method(name: 'sh', type: 'Object')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            assertThat(metadata.steps).hasSize(3)
            assertThat(metadata.steps.keys).containsExactlyInAnyOrder("echo", "error", "sh")
        }

        @Test
        fun `handles multiple contributors`() {
            val gdsl = """
                def scriptCtx = context(scope: scriptScope())
                contributor(scriptCtx) {
                    method(name: 'echo', type: 'void')
                }

                def nodeCtx = context(scope: closureScope())
                contributor(nodeCtx) {
                    method(name: 'sh', type: 'Object')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            assertThat(metadata.steps).hasSize(2)
            assertThat(metadata.steps["echo"]?.scope).isEqualTo(StepScope.GLOBAL)
            assertThat(metadata.steps["sh"]?.scope).isEqualTo(StepScope.NODE)
        }
    }

    @Nested
    inner class `Overload Merging` {

        @Test
        fun `merges positional and named overloads into one step`() {
            val gdsl = """
                contributor([context()]) {
                    method(name: 'parallel', type: 'Object', params: ['closures':'java.util.Map'], doc: 'Execute in parallel')
                    method(name: 'parallel', type: 'Object', namedParams: [
                        parameter(name: 'closures', type: 'java.util.Map'),
                        parameter(name: 'failFast', type: 'boolean'),
                    ], doc: 'Execute in parallel')
                }
            """.trimIndent()

            val metadata = convertGdslToMetadata(
                gdslContent = gdsl,
                pluginInfo = PluginInfo("test", "1.0"),
                inputs = ExtractionInputs(
                    jenkinsVersion = "2.426.3",
                    gdslSha256 = "sha",
                    pluginsManifestSha256 = "sha",
                ),
            )

            assertThat(metadata.steps).hasSize(1)
            val parallel = metadata.steps["parallel"]
            assertThat(parallel).isNotNull
            assertThat(parallel?.positionalParams).containsExactly("closures")
            assertThat(parallel?.namedParams?.keys).containsExactlyInAnyOrder("closures", "failFast")
            assertThat(parallel?.namedParams?.get("closures")?.type).isEqualTo("java.util.Map")
            assertThat(parallel?.namedParams?.get("failFast")?.type).isEqualTo("boolean")
            assertThat(parallel?.documentation).isEqualTo("Execute in parallel")
            assertThat(parallel?.returnType).isEqualTo("Object")
        }
    }
}
