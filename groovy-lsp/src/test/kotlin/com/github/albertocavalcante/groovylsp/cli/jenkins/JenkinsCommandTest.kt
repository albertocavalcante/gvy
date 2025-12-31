package com.github.albertocavalcante.groovylsp.cli.jenkins

import com.github.albertocavalcante.groovyjenkins.extraction.PluginsParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Tests for Jenkins CLI command functionality.
 *
 * These tests verify the underlying logic used by the CLI commands
 * rather than testing the Clikt command wrapper directly, as the
 * Clikt testing API requires complex context setup.
 */
class JenkinsCommandTest {

    @Nested
    inner class `PluginsParser Integration` {

        @Test
        fun `parses plugins from file for extract command`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText(
                """
                workflow-basic-steps:latest
                git:5.2.0
                docker-workflow:1.0
                """.trimIndent(),
            )

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).hasSize(3)
            assertThat(plugins[0].id).isEqualTo("workflow-basic-steps")
            assertThat(plugins[1].id).isEqualTo("git")
            assertThat(plugins[2].id).isEqualTo("docker-workflow")
        }

        @Test
        fun `detects duplicate plugins for validate command`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText(
                """
                git:5.2.0
                workflow-basic-steps:latest
                git:5.3.0
                """.trimIndent(),
            )

            val plugins = PluginsParser.parse(pluginsTxt)
            val duplicates = plugins.groupBy { it.id }
                .filter { it.value.size > 1 }
                .keys

            assertThat(duplicates).containsExactly("git")
        }

        @Test
        fun `returns empty for comments-only file`(@TempDir tempDir: Path) {
            val pluginsTxt = tempDir.resolve("plugins.txt")
            pluginsTxt.writeText("# Only comments\n# No plugins")

            val plugins = PluginsParser.parse(pluginsTxt)

            assertThat(plugins).isEmpty()
        }
    }

    @Nested
    inner class `Command Existence` {

        @Test
        fun `JenkinsCommand can be instantiated`() {
            val command = JenkinsCommand()
            assertThat(command.commandName).isEqualTo("jenkins")
        }

        @Test
        fun `ExtractCommand can be instantiated`() {
            val command = ExtractCommand()
            assertThat(command.commandName).isEqualTo("extract")
        }

        @Test
        fun `ValidateCommand can be instantiated`() {
            val command = ValidateCommand()
            assertThat(command.commandName).isEqualTo("validate")
        }
    }
}
