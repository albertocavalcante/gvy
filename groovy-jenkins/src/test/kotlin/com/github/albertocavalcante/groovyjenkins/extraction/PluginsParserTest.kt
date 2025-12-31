package com.github.albertocavalcante.groovyjenkins.extraction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * TDD tests for PluginsParser.
 *
 * Tests the parsing of plugins.txt files which define Jenkins plugins
 * in the format: plugin-id:version
 */
class PluginsParserTest {

    @Nested
    inner class `Basic Parsing` {

        @Test
        fun `parses single plugin with specific version`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText("workflow-basic-steps:1058.v1")

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).hasSize(1)
            assertThat(plugins[0]).isEqualTo(
                PluginSpec(id = "workflow-basic-steps", version = "1058.v1"),
            )
        }

        @Test
        fun `parses plugin with latest version`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText("git:latest")

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).hasSize(1)
            assertThat(plugins[0]).isEqualTo(
                PluginSpec(id = "git", version = "latest"),
            )
        }

        @Test
        fun `parses multiple plugins`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText(
                """
                workflow-basic-steps:1058.v1
                git:5.2.0
                docker-workflow:latest
                """.trimIndent(),
            )

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).hasSize(3)
            assertThat(plugins.map { it.id }).containsExactly(
                "workflow-basic-steps",
                "git",
                "docker-workflow",
            )
        }
    }

    @Nested
    inner class `Comments and Whitespace` {

        @Test
        fun `ignores comment lines starting with hash`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText(
                """
                # This is a comment
                workflow-basic-steps:latest
                # Another comment
                git:latest
                """.trimIndent(),
            )

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).hasSize(2)
            assertThat(plugins.map { it.id }).containsExactly(
                "workflow-basic-steps",
                "git",
            )
        }

        @Test
        fun `ignores empty lines`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText(
                """
                workflow-basic-steps:latest
                
                git:latest
                
                """.trimIndent(),
            )

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).hasSize(2)
        }

        @Test
        fun `trims whitespace from lines`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText("  workflow-basic-steps:latest  ")

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).hasSize(1)
            assertThat(plugins[0].id).isEqualTo("workflow-basic-steps")
        }

        @Test
        fun `handles inline comments`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText("workflow-basic-steps:latest  # core plugin")

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).hasSize(1)
            assertThat(plugins[0].id).isEqualTo("workflow-basic-steps")
            assertThat(plugins[0].version).isEqualTo("latest")
        }
    }

    @Nested
    inner class `Determinism` {

        @Test
        fun `preserves order of plugins`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText(
                """
                git:latest
                workflow-basic-steps:latest
                docker-workflow:latest
                """.trimIndent(),
            )

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins.map { it.id }).containsExactly(
                "git",
                "workflow-basic-steps",
                "docker-workflow",
            )
        }

        @Test
        fun `same input produces same output`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText(
                """
                git:5.2.0
                workflow-basic-steps:1058.v1
                """.trimIndent(),
            )

            val result1 = PluginsParser.parse(pluginsTxt)
            val result2 = PluginsParser.parse(pluginsTxt)

            assertThat(result1).isEqualTo(result2)
        }
    }

    @Nested
    inner class `Edge Cases` {

        @Test
        fun `returns empty list for empty file`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText("")

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).isEmpty()
        }

        @Test
        fun `returns empty list for file with only comments`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText(
                """
                # Comment 1
                # Comment 2
                """.trimIndent(),
            )

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).isEmpty()
        }
    }
}
