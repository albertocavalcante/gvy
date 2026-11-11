package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ScenarioContextTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `a null response exists at the root and can equal explicit null`() {
        val context = context()

        assertThat(context.evaluateCheck(JsonNull, check("$", ExpectationType.EXISTS))).isTrue()
        assertThat(context.evaluateCheck(JsonNull, check("$", ExpectationType.EQUALS))).isTrue()
        assertThat(context.evaluateCheck(JsonNull, check("$", ExpectationType.NOT_EXISTS), quiet = true)).isFalse()
    }

    @Test
    fun `a child missing from a null response does not become a present null`() {
        val context = context()

        assertThat(context.evaluateCheck(JsonNull, check("$.missing", ExpectationType.NOT_EXISTS))).isTrue()
        assertThat(context.evaluateCheck(JsonNull, check("$.missing", ExpectationType.EQUALS), quiet = true)).isFalse()
        assertThatThrownBy { context.evaluateCheck(JsonNull, check("$.missing", ExpectationType.EQUALS)) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("path not found")
    }

    @Test
    fun `an object null field exists while an absent sibling does not`() {
        val context = context()
        val response = Json.parseToJsonElement("""{"detail":null}""")

        assertThat(context.evaluateCheck(response, check("$.detail", ExpectationType.EQUALS))).isTrue()
        assertThat(context.evaluateCheck(response, check("$.missing", ExpectationType.NOT_EXISTS))).isTrue()
    }

    @Test
    fun `array queries preserve literal null values`() {
        val context = context()
        val response = Json.parseToJsonElement("""{"items":[{"detail":null},{"detail":"wrong"}]}""")
        val containsNull = JsonExpectation(type = ExpectationType.CONTAINS, value = JsonNull)

        assertThat(context.evaluateCheck(response, check("$.items[0].detail", ExpectationType.EQUALS))).isTrue()
        assertThat(context.evaluateCheck(response, JsonCheck("$.items[*].detail", containsNull))).isTrue()
        assertThat(
            context.evaluateCheck(response, JsonCheck("$.items[1:].detail", containsNull), quiet = true),
        ).isFalse()
    }

    @Test
    fun `empty retains its optional-content semantics for null and missing paths`() {
        val context = context()

        assertThat(context.evaluateCheck(JsonNull, check("$", ExpectationType.EMPTY))).isTrue()
        assertThat(context.evaluateCheck(JsonNull, check("$.missing", ExpectationType.EMPTY))).isTrue()
        assertThat(
            context.evaluateCheck(Json.parseToJsonElement("{}"), check("$.missing", ExpectationType.EMPTY)),
        ).isTrue()
    }

    private fun context(): ScenarioContext = ScenarioContext(
        definition = ScenarioDefinition(Scenario(name = "null-response", steps = emptyList())),
        session = mockk(relaxed = true),
        workspace = ScenarioWorkspace(workspaceRoot),
        jsonPathConfig = Configuration.builder()
            .jsonProvider(JsonSmartJsonProvider())
            .mappingProvider(JsonSmartMappingProvider())
            .build(),
    )

    private fun check(path: String, type: ExpectationType): JsonCheck = JsonCheck(
        jsonPath = path,
        expect = JsonExpectation(type = type, value = if (type == ExpectationType.EQUALS) JsonNull else null),
    )
}
