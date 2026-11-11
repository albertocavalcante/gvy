package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GoldenArtifactPolicyTest {
    @TempDir
    lateinit var root: Path

    private lateinit var context: ScenarioContext
    private lateinit var actual: Path
    private lateinit var expected: Path
    private val previousProperties = mutableMapOf<String, String?>()

    @BeforeEach
    fun prepareGoldenFiles() {
        previousProperties.putAll(PROPERTIES.associateWith(System::getProperty))
        System.setProperty(GOLDEN_DIRECTORY, root.resolve("golden").toString())
        System.setProperty(UPDATE_GOLDEN, "true")
        System.clearProperty(GOLDEN_MARKER)
        actual = root.resolve("actual")
        expected = root.resolve("golden/expected")
        Files.createDirectories(expected.parent)
        Files.writeString(actual, "observed\n")
        Files.writeString(expected, "committed\n")
        context = ScenarioContext(
            definition = ScenarioDefinition(Scenario(name = "golden-policy", steps = emptyList())),
            session = mockk(relaxed = true),
            workspace = ScenarioWorkspace(root),
            jsonPathConfig = Configuration.defaultConfiguration(),
        )
    }

    @AfterEach
    fun restoreProperties() {
        previousProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["CI", "GITHUB_ACTIONS"])
    fun `CI update attempts leave the committed golden unchanged`(variable: String) {
        assertThatThrownBy { execute(environment = mapOf(variable to "true")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(variable)
        assertThat(Files.readString(expected)).isEqualTo("committed\n")
    }

    @Test
    fun `Bazel update attempts leave the committed golden unchanged`() {
        assertThatThrownBy { execute(environment = mapOf("TEST_TARGET" to "//tests:e2e_test")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Bazel")
        assertThat(Files.readString(expected)).isEqualTo("committed\n")
    }

    @Test
    fun `Bazel marker refuses updates without relying on environment`() {
        System.setProperty(GOLDEN_MARKER, "runfiles/golden/marker")
        assertThatThrownBy { execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Bazel")
        assertThat(Files.readString(expected)).isEqualTo("committed\n")
    }

    @Test
    fun `local updates remain allowed`() {
        execute()
        assertThat(Files.readString(expected)).isEqualTo("observed\n")
    }

    @Test
    fun `CI comparisons remain allowed`() {
        System.setProperty(UPDATE_GOLDEN, "false")
        Files.writeString(expected, "observed\n")
        assertThatCode { execute(environment = mapOf("CI" to "true")) }.doesNotThrowAnyException()
    }

    @ParameterizedTest
    @ValueSource(strings = ["../outside", "nested/../../outside"])
    fun `traversal is rejected before an outside golden can be overwritten`(name: String) {
        val outside = root.resolve("outside")
        Files.writeString(outside, "outside\n")
        assertThatThrownBy { execute(name) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.readString(outside)).isEqualTo("outside\n")
    }

    @Test
    fun `absolute golden paths cannot overwrite an outside file`() {
        val outside = root.resolve("outside")
        Files.writeString(outside, "outside\n")
        assertThatThrownBy { execute(outside.toString()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.readString(outside)).isEqualTo("outside\n")
    }

    @Test
    fun `blank expected names fail before checking actual file existence`() {
        Files.delete(actual)
        assertThatThrownBy { execute("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("blank")
    }

    @Test
    fun `relative paths normalized inside the golden directory remain allowed`() {
        execute("nested/../expected")
        assertThat(Files.readString(expected)).isEqualTo("observed\n")
    }

    @Test
    fun `a golden symlink cannot overwrite an outside file`() {
        val outside = root.resolve("outside")
        Files.writeString(outside, "outside\n")
        Files.delete(expected)
        Files.createSymbolicLink(expected, outside)
        assertThatThrownBy { execute() }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.readString(outside)).isEqualTo("outside\n")
    }

    @Test
    fun `a directory symlink cannot create an outside golden`() {
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(expected.parent.resolve("linked"), outside)
        assertThatThrownBy { execute("linked/new") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(outside.resolve("new")).doesNotExist()
    }

    @Test
    fun `a configured golden directory may itself be a symlink`() {
        val alias = Files.createSymbolicLink(root.resolve("golden-alias"), expected.parent)
        System.setProperty(GOLDEN_DIRECTORY, alias.toString())
        execute()
        assertThat(Files.readString(expected)).isEqualTo("observed\n")
    }

    @Test
    fun `Bazel comparisons can read a committed runfile symlink`() {
        val committed = root.resolve("committed")
        Files.writeString(committed, "observed\n")
        Files.delete(expected)
        Files.createSymbolicLink(expected, committed)
        System.setProperty(UPDATE_GOLDEN, "false")
        execute(environment = mapOf("TEST_TARGET" to "//tests:e2e_test"))
    }

    private fun execute(name: String = "expected", environment: Map<String, String?> = emptyMap()) {
        GoldenAssertStepExecutor(environment).execute(
            ScenarioStep.GoldenAssert(actual.toString(), name, GoldenMode.TEXT),
            context,
        )
    }

    private companion object {
        const val GOLDEN_DIRECTORY = "groovy.lsp.e2e.goldenDir"
        const val GOLDEN_MARKER = "$GOLDEN_DIRECTORY.marker"
        const val UPDATE_GOLDEN = "groovy.lsp.e2e.updateGolden"
        val PROPERTIES = listOf(GOLDEN_DIRECTORY, GOLDEN_MARKER, UPDATE_GOLDEN)
    }
}
