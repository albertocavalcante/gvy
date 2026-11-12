package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.mockk.mockk
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path

class ExactJsonRenderingTest {
    @TempDir
    lateinit var root: Path

    private val forms = WorkspaceForms.from(listOf("/fixture/ws" to "file:///fixture/ws/"))

    @TestFactory
    fun `embedded structured interpolation preserves nested numeric text`(): List<DynamicTest> =
        numericLiterals.map { literal ->
            dynamicTest(literal) {
                val context = context()
                context.setVariable("payload", Json.parseToJsonElement("""{"number":$literal,"nested":[$literal]}"""))

                assertThat(context.interpolateString("before {{payload}} after")).isEqualTo(
                    """
                    before {
                        "number": $literal,
                        "nested": [
                            $literal
                        ]
                    } after
                    """.trimIndent(),
                )
            }
        }

    @TestFactory
    fun `snapshots preserve numeric text with sorted keys and normalized workspace strings`(): List<DynamicTest> =
        numericLiterals.map { literal ->
            dynamicTest(literal) {
                val node = Json.parseToJsonElement("""{"z":[$literal],"a":"file:///fixture/ws/A.groovy"}""")

                assertThat(Snapshots.render(node, forms)).isEqualTo(
                    """
                    {
                        "a": "{{workspace.uri}}A.groovy",
                        "z": [
                            $literal
                        ]
                    }

                    """.trimIndent(),
                )
            }
        }

    @TestFactory
    fun `numeric snapshots compare update and recover the exact expected file`(): List<DynamicTest> =
        numericLiterals.mapIndexed { index, literal ->
            dynamicTest(literal) {
                val name = "numeric-$index.json"
                val file = root.resolve(name)
                val node = Json.parseToJsonElement("""{"number":$literal}""")
                val expected = "{\n    \"number\": $literal\n}\n"
                Files.writeString(file, expected)

                Snapshots.verify(root, name, node, forms, update = false)
                Snapshots.verify(root, name, node, forms, update = true)
                assertThat(Files.readString(file)).isEqualTo(expected)
                Files.writeString(file, "changed\n")
                assertThatThrownBy { Snapshots.verify(root, name, node, forms, update = false) }
                    .isInstanceOf(AssertionFailedError::class.java)
                Snapshots.verify(root, name, node, forms, update = true)
                assertThat(Files.readString(file)).isEqualTo(expected)
            }
        }

    @Test
    fun `a whole placeholder retains the original JSON value and its type`() {
        val context = context()
        listOf(
            Json.parseToJsonElement("""{"number":1e2147483649}"""),
            Json.parseToJsonElement("""[123456789012345678901234567890]"""),
            Json.parseToJsonElement("0.123456789012345678901234567890"),
            JsonPrimitive("123456789012345678901234567890"),
            JsonPrimitive(false),
            JsonNull,
        ).forEach { value ->
            context.setVariable("payload", value)

            assertThat(context.interpolateNode(JsonPrimitive("{{payload}}"))).isSameAs(value)
        }
    }

    @Test
    fun `compact interpolation keeps its configured formatting`() {
        val context = context(Json { prettyPrint = false })
        context.setVariable("payload", Json.parseToJsonElement("""{"number":1e400}"""))

        assertThat(context.interpolateString("before {{payload}} after"))
            .isEqualTo("""before {"number":1e400} after""")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `pretty interpolation keeps its configured indentation`() {
        val context = context(
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            },
        )
        context.setVariable("payload", Json.parseToJsonElement("""{"number":1e400}"""))

        assertThat(context.interpolateString("before {{payload}} after"))
            .isEqualTo("before {\n  \"number\": 1e400\n} after")
    }

    @Test
    fun `null booleans numeric strings and escapes retain their rendering behavior`() {
        val node = Json.parseToJsonElement(
            """{"text":"quote \" slash \\ line\n","number":"1e400","nil":null,"flag":false}""",
        )
        val context = context()
        context.setVariable("payload", node)

        assertThat(context.interpolateString("before {{payload}} after")).isEqualTo(
            """
            before {
                "text": "quote \" slash \\ line\n",
                "number": "1e400",
                "nil": null,
                "flag": false
            } after
            """.trimIndent(),
        )
        assertThat(Snapshots.render(node, forms)).isEqualTo(
            """
            {
                "flag": false,
                "nil": null,
                "number": "1e400",
                "text": "quote \" slash \\ line\n"
            }

            """.trimIndent(),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @TestFactory
    fun `malformed unquoted numeric tokens are rejected at both actual renderers`(): List<DynamicTest> =
        listOf("NaN", "Infinity", "-Infinity", "+1", "01", "1.", ".5", "1e", "1e+", "1 2", "1,false", "{}", "")
            .flatMap { token ->
                Site.entries.map { site ->
                    dynamicTest("$site $token") {
                        val node = JsonObject(mapOf("number" to JsonUnquotedLiteral(token)))

                        assertThatThrownBy { render(site, node) }.isInstanceOf(SerializationException::class.java)
                    }
                }
            }

    @TestFactory
    fun `default nonfinite numeric primitives remain rejected at both renderers`(): List<DynamicTest> =
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).flatMap { number ->
            Site.entries.map { site ->
                dynamicTest("$site $number") {
                    val node = JsonObject(mapOf("number" to JsonPrimitive(number)))

                    assertThatThrownBy { render(site, node) }.isInstanceOf(SerializationException::class.java)
                }
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `an invalid numeric snapshot update never creates the expected file`() {
        val node = JsonObject(mapOf("number" to JsonUnquotedLiteral("1,false")))

        assertThatThrownBy { Snapshots.verify(root, "invalid.json", node, forms, update = true) }
            .isInstanceOf(SerializationException::class.java)
        assertThat(Files.exists(root.resolve("invalid.json"))).isFalse()
    }

    private fun render(site: Site, node: JsonElement): String = when (site) {
        Site.INTERPOLATION -> context().apply { setVariable("payload", node) }
            .interpolateString("before {{payload}} after")
        Site.SNAPSHOT -> Snapshots.render(node, forms)
    }

    private fun context(json: Json = Json { prettyPrint = true }): ScenarioContext = ScenarioContext(
        definition = ScenarioDefinition(Scenario(name = "exact-json-rendering", steps = emptyList())),
        session = mockk(relaxed = true),
        workspace = ScenarioWorkspace(root),
        json = json,
        jsonPathConfig = Configuration.builder()
            .jsonProvider(JsonSmartJsonProvider())
            .mappingProvider(JsonSmartMappingProvider())
            .build(),
    )

    private enum class Site { INTERPOLATION, SNAPSHOT }

    private val numericLiterals = listOf(
        "123456789012345678901234567890",
        "0.123456789012345678901234567890",
        "1e400",
        "1e2147483649",
        "-1e2147483649",
        "1e-2147483649",
        "-0.0",
        "1.2300e+10",
    )
}
