package com.github.albertocavalcante.groovyjenkins.extraction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for MetadataOutputGenerator.
 */
class MetadataOutputGeneratorTest {

    @Nested
    inner class `generate` {

        @Test
        fun `converts scanned steps to metadata`() {
            val steps = listOf(
                ScannedStep(
                    className = "org.example.EchoStep",
                    simpleName = "EchoStep",
                    functionName = "echo",
                    takesBlock = false,
                    constructorParams = listOf(
                        ExtractedParam("message", "java.lang.String", true),
                    ),
                    setterParams = emptyList(),
                    pluginId = "workflow-basic-steps",
                ),
            )

            val metadata = MetadataOutputGenerator.generate(steps)

            assertThat(metadata.version).isEqualTo("1.0")
            assertThat(metadata.steps).containsKey("echo")
            assertThat(metadata.steps["echo"]?.functionName).isEqualTo("echo")
            assertThat(metadata.steps["echo"]?.plugin).isEqualTo("workflow-basic-steps")
            assertThat(metadata.steps["echo"]?.parameters).containsKey("message")
        }

        @Test
        fun `sorts steps by function name`() {
            val steps = listOf(
                ScannedStep("Z", "ZStep", "z", false, emptyList(), emptyList()),
                ScannedStep("A", "AStep", "a", false, emptyList(), emptyList()),
                ScannedStep("M", "MStep", "m", false, emptyList(), emptyList()),
            )

            val metadata = MetadataOutputGenerator.generate(steps)

            assertThat(metadata.steps.keys.toList()).isEqualTo(listOf("a", "m", "z"))
        }

        @Test
        fun `combines constructor and setter params`() {
            val steps = listOf(
                ScannedStep(
                    className = "Retry",
                    simpleName = "RetryStep",
                    functionName = "retry",
                    takesBlock = true,
                    constructorParams = listOf(ExtractedParam("count", "int", true)),
                    setterParams = listOf(ExtractedParam("conditions", "java.util.List", false)),
                ),
            )

            val metadata = MetadataOutputGenerator.generate(steps)

            val params = metadata.steps["retry"]?.parameters
            assertThat(params).containsKeys("count", "conditions")
            assertThat(params?.get("count")?.required).isTrue()
            assertThat(params?.get("conditions")?.required).isFalse()
        }

        @Test
        fun `constructor params should override setter params with same name`() {
            // Case: A parameter is in both @DataBoundConstructor (required) and @DataBoundSetter (optional).
            // The required status from constructor should take precedence.
            val steps = listOf(
                ScannedStep(
                    className = "Mixed",
                    simpleName = "MixedStep",
                    functionName = "mixed",
                    takesBlock = false,
                    // 'script' is mandatory in constructor
                    constructorParams = listOf(ExtractedParam("script", "String", true)),
                    // 'script' also appears as a setter (optional)
                    setterParams = listOf(ExtractedParam("script", "String", false)),
                ),
            )

            val metadata = MetadataOutputGenerator.generate(steps)
            val params = metadata.steps["mixed"]?.parameters

            // BUG REPRODUCTION: Currently this assertion might fail if logic is flawed
            assertThat(params?.get("script")?.required)
                .describedAs("Constructor param (required) should take precedence over setter param")
                .isTrue()
        }

        @Test
        fun `handles duplicate keys by overwriting`() {
            val steps = listOf(
                ScannedStep("A", "StepA", "conflict", false, emptyList(), emptyList(), "plugin-1"),
                ScannedStep("B", "StepB", "conflict", false, emptyList(), emptyList(), "plugin-2"),
            )

            // The list is sorted by function name (both "conflict") and then processed.
            // In the map builder, the later one in the list overwrites the former.
            // Since our sort is stable for equal function names
            // (but steps might be sorted by classname inside scanner),
            // we should rely on the map behavior.
            val metadata = MetadataOutputGenerator.generate(steps)

            assertThat(metadata.steps).hasSize(1)
            assertThat(metadata.steps["conflict"]?.className).isIn("A", "B")
            // We mainly want to ensure no exception is thrown and valid metadata is produced
        }
    }

    @Nested
    inner class `toJsonString` {

        @Test
        fun `produces deterministic output`() {
            val steps = listOf(
                ScannedStep("B", "B", "b", false, emptyList(), emptyList()),
                ScannedStep("A", "A", "a", false, emptyList(), emptyList()),
            )

            val json1 = MetadataOutputGenerator.toJsonString(MetadataOutputGenerator.generate(steps))
            val json2 = MetadataOutputGenerator.toJsonString(MetadataOutputGenerator.generate(steps))

            assertThat(json1).isEqualTo(json2)
        }
    }

    @Nested
    inner class `writeToFile` {

        @Test
        fun `writes valid JSON file`(@TempDir tempDir: Path) {
            val steps = listOf(
                ScannedStep("Echo", "EchoStep", "echo", false, emptyList(), emptyList()),
            )
            val outputPath = tempDir.resolve("metadata.json")

            MetadataOutputGenerator.writeToFile(MetadataOutputGenerator.generate(steps), outputPath)

            val content = outputPath.toFile().readText()
            assertThat(content).contains("\"echo\"")
            assertThat(content).contains("\"version\"")
        }
    }
}
