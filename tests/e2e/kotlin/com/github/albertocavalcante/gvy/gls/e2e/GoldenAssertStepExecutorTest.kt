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
import java.nio.file.Files
import java.nio.file.Path

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GoldenAssertStepExecutorTest {
    @TempDir
    lateinit var root: Path

    private lateinit var context: ScenarioContext
    private val previousProperties = mutableMapOf<String, String?>()

    @BeforeEach
    fun prepareGoldenDirectory() {
        previousProperties.putAll(PROPERTIES.associateWith(System::getProperty))
        System.setProperty(GOLDEN_DIRECTORY, root.resolve("golden").toString())
        System.setProperty(UPDATE_GOLDEN, "false")
        context = ScenarioContext(
            definition = ScenarioDefinition(Scenario(name = "golden-boundary", steps = emptyList())),
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

    @Test
    fun `equal non UTF-8 binary files compare successfully`() {
        assertThatCode { compare(byteArrayOf(-1), byteArrayOf(-1), GoldenMode.BINARY) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `different non UTF-8 binary files report a comparison failure`() {
        assertThatThrownBy { compare(byteArrayOf(-1, 0), byteArrayOf(-1, 1), GoldenMode.BINARY) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("Binary content mismatch")
    }

    @Test
    fun `text comparison preserves significant edge whitespace`() {
        assertThatThrownBy {
            compare(" result \n".toByteArray(), "result\n".toByteArray(), GoldenMode.TEXT)
        }.isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("Text content mismatch")
    }

    @Test
    fun `equal text including edge whitespace compares successfully`() {
        assertThatCode {
            compare(" result \n".toByteArray(), " result \n".toByteArray(), GoldenMode.TEXT)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `updated text with workspace paths verifies without losing whitespace`() {
        val realRoot = root.toRealPath()
        val text = "  path=$realRoot\nuri=${realRoot.toUri().toString().trimEnd('/')}\n"
        val actualFile = root.resolve("actual")
        Files.writeString(actualFile, text)
        val step = ScenarioStep.GoldenAssert(actualFile.toString(), "expected", GoldenMode.TEXT)
        val executor = GoldenAssertStepExecutor()

        System.setProperty(UPDATE_GOLDEN, "true")
        executor.execute(step, context)
        assertThat(Files.readString(root.resolve("golden/expected")))
            .isEqualTo("  path={{workspace}}\nuri=file://{{workspace}}\n")

        System.setProperty(UPDATE_GOLDEN, "false")
        assertThatCode { executor.execute(step, context) }.doesNotThrowAnyException()
    }

    @Test
    fun `JSON comparison keeps structural equality across layout and key order`() {
        assertThatCode {
            compare("{\"a\":1,\"b\":2}".toByteArray(), "{ \"b\": 2, \"a\": 1 }".toByteArray(), GoldenMode.JSON)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `NDJSON comparison keeps structural equality within each line`() {
        assertThatCode {
            compare(
                "{\"a\":1}\n{\"b\":2}\n".toByteArray(),
                "{ \"a\": 1 }\n{ \"b\": 2 }\n".toByteArray(),
                GoldenMode.NDJSON,
            )
        }.doesNotThrowAnyException()
    }

    private fun compare(actual: ByteArray, expected: ByteArray, mode: GoldenMode) {
        val actualFile = root.resolve("actual")
        val expectedFile = root.resolve("golden/expected")
        Files.write(actualFile, actual)
        Files.createDirectories(expectedFile.parent)
        Files.write(expectedFile, expected)

        GoldenAssertStepExecutor().execute(
            ScenarioStep.GoldenAssert(actual = actualFile.toString(), expected = "expected", mode = mode),
            context,
        )
    }

    private companion object {
        const val GOLDEN_DIRECTORY = "groovy.lsp.e2e.goldenDir"
        const val UPDATE_GOLDEN = "groovy.lsp.e2e.updateGolden"
        val PROPERTIES = listOf(GOLDEN_DIRECTORY, UPDATE_GOLDEN)
    }
}
