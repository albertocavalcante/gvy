package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DiscoveryScenarioAssertionsTest {
    @TempDir
    lateinit var workspaceRoot: Path

    private val definition by lazy {
        ScenarioLoader().load(Path.of(resolveScenarioDir()).resolve("discover-tests.yaml"))
    }
    private val checks get() = definition.scenario.steps.filterIsInstance<ScenarioStep.Assert>().single().checks

    private fun context(): ScenarioContext = ScenarioContext(
        definition = definition,
        session = mockk(),
        workspace = ScenarioWorkspace(workspaceRoot),
        jsonPathConfig = Configuration.builder()
            .jsonProvider(JsonSmartJsonProvider())
            .mappingProvider(JsonSmartMappingProvider())
            .build(),
    )

    @Test
    fun `discovery assertions accept a suite with a named test and a source line`() {
        val result = Json.parseToJsonElement(discovery())
        val context = context()

        assertThat(checks).isNotEmpty()
        assertThat(checks.all { context.evaluateCheck(result, it) }).isTrue()
    }

    @TestFactory
    fun `discovery assertions reject wrong results even when their paths exist`(): List<DynamicTest> = listOf(
        Triple("no suites", "$.length()", "[]"),
        Triple("wrong suite", "$[0].suite", discovery(suite = "OtherSpec")),
        Triple("no tests", "$[0].tests.length()", """[{"suite":"CalculatorSpec","tests":[]}]"""),
        Triple("blank test name", "$[0].tests[0].test", discovery(test = "")),
        Triple("invalid source line", "$[0].tests[0].line", discovery(line = 0)),
    ).map { (name, jsonPath, payload) ->
        dynamicTest(name) {
            val check = checks.single { it.jsonPath == jsonPath }
            assertThat(context().evaluateCheck(Json.parseToJsonElement(payload), check, quiet = true))
                .describedAs(name)
                .isFalse()
        }
    }

    private fun discovery(suite: String = "CalculatorSpec", test: String = "adds numbers", line: Int = 1): String =
        """[{"suite":"$suite","tests":[{"test":"$test","line":$line}]}]"""
}
