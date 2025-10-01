package com.github.albertocavalcante.groovylsp.codenarc

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization tests for CodeNarcConfigurationManager.
 * These tests capture the CURRENT BEHAVIOR before refactoring to ensure we maintain
 * the same functionality while changing the implementation to use .groovy files only.
 */
class CodeNarcConfigurationManagerCharacterizationTest {

    private lateinit var manager: CodeNarcConfigurationManager

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        manager = CodeNarcConfigurationManager()
    }

    @Test
    fun `should load default ruleset for null workspace`() {
        val config = manager.loadConfiguration(null)

        // Verify default behavior
        assertEquals("default-groovy", config.configSource)
        assertFalse(config.isJenkinsProject)
        assertNull(config.propertiesFile)

        // Verify ruleset contains expected rules (behavior, not implementation)
        assertTrue(config.ruleSetString.contains("TrailingWhitespace"))
        assertTrue(config.ruleSetString.contains("MethodName"))
        assertTrue(config.ruleSetString.contains("VariableName"))
        assertTrue(config.ruleSetString.contains("FieldName"))
        assertTrue(config.ruleSetString.contains("rulesets/basic.xml"))
        assertTrue(config.ruleSetString.contains("rulesets/imports.xml"))
    }

    @Test
    fun `should load default ruleset for regular workspace`() {
        // Create regular workspace (no Jenkins indicators)
        val workspaceUri = tempDir.toUri()

        val config = manager.loadConfiguration(workspaceUri)

        assertThat(config.configSource).isEqualTo("default-groovy")
        assertThat(config.isJenkinsProject).isFalse()
        assertThat(config.ruleSetString).contains("TrailingWhitespace")
        assertThat(config.ruleSetString).contains("MethodName")
    }

    @Test
    fun `should load Jenkins ruleset for Jenkins workspace`() {
        // Create Jenkins workspace
        Files.createFile(tempDir.resolve("Jenkinsfile"))
        val workspaceUri = tempDir.toUri()

        val config = manager.loadConfiguration(workspaceUri)

        assertThat(config.configSource).isEqualTo("default-jenkins")
        assertThat(config.isJenkinsProject).isTrue()

        // Verify Jenkins-specific behavior
        assertThat(config.ruleSetString).contains("TrailingWhitespace")
        assertThat(config.ruleSetString).contains("ignoreVariableNames")
        assertThat(config.ruleSetString).contains("env,params,currentBuild")
        assertThat(config.ruleSetString).contains("pipeline")
        assertThat(config.ruleSetString).contains("maxLines = 150") // Jenkins has higher limits
    }

    @Test
    fun `should prefer custom configuration file over defaults`() {
        // Create workspace with custom .codenarc file
        val customRules = """
            ruleset {
                description 'Custom test ruleset'
                rule('TrailingWhitespace')
            }
        """.trimIndent()

        Files.writeString(tempDir.resolve(".codenarc"), customRules)
        val workspaceUri = tempDir.toUri()

        val config = manager.loadConfiguration(workspaceUri)

        assertThat(config.configSource).isEqualTo(".codenarc")
        assertThat(config.ruleSetString).isEqualTo(customRules)
        // Jenkins detection should still work
        assertThat(config.isJenkinsProject).isFalse()
    }

    @Test
    fun `should detect Jenkins project with custom configuration`() {
        // Create Jenkins workspace with custom config
        Files.createFile(tempDir.resolve("Jenkinsfile"))
        val customRules = "ruleset { rule('TrailingWhitespace') }"
        Files.writeString(tempDir.resolve(".codenarc"), customRules)
        val workspaceUri = tempDir.toUri()

        val config = manager.loadConfiguration(workspaceUri)

        assertThat(config.configSource).isEqualTo(".codenarc")
        assertThat(config.isJenkinsProject).isTrue() // Should still detect Jenkins
        assertThat(config.ruleSetString).isEqualTo(customRules)
    }

    @Test
    fun `should handle properties file detection`() {
        // Create workspace with properties file
        Files.writeString(tempDir.resolve("codenarc.properties"), "test.property=value")
        val workspaceUri = tempDir.toUri()

        val config = manager.loadConfiguration(workspaceUri)

        assertThat(config.propertiesFile).isNotNull()
        assertThat(config.propertiesFile).endsWith("codenarc.properties")
    }

    @Test
    fun `should handle server configuration overrides`() {
        val serverConfig = ServerConfiguration(codeNarcAutoDetect = false)

        val config = manager.loadConfiguration(tempDir.toUri(), serverConfig)

        // When auto-detect is disabled, should not find properties file
        assertThat(config.propertiesFile).isNull()
    }

    // Helper method for cleaner assertions
    private fun assertThat(actual: String?): StringAssertion = StringAssertion(actual)
    private fun assertThat(actual: Boolean): BooleanAssertion = BooleanAssertion(actual)

    class StringAssertion(private val actual: String?) {
        fun isEqualTo(expected: String) = assertEquals(expected, actual)
        fun isNotNull() = assertNotNull(actual)
        fun isNull() = assertNull(actual)
        fun contains(substring: String) = assertTrue(
            actual?.contains(substring) == true,
            "Expected '$actual' to contain '$substring'",
        )
        fun endsWith(suffix: String) = assertTrue(actual?.endsWith(suffix) == true)
    }

    class BooleanAssertion(private val actual: Boolean) {
        fun isTrue() = assertTrue(actual)
        fun isFalse() = assertFalse(actual)
    }
}
