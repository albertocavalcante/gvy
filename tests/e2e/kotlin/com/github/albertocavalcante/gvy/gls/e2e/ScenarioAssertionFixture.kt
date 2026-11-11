package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Path

/** Runs the checked-in YAML oracle, without its xfail classifier or a server process. */
internal class ScenarioAssertionFixture(file: String, root: Path, source: String? = null) {
    val definition = ScenarioLoader().load(Path.of(resolveScenarioDir()).resolve(file))
    val context = ScenarioContext(
        definition = definition,
        session = mockk(),
        workspace = ScenarioWorkspace(root),
        jsonPathConfig = Configuration.builder()
            .jsonProvider(JsonSmartJsonProvider())
            .mappingProvider(JsonSmartMappingProvider())
            .build(),
    )
    private val assertion = definition.scenario.steps.filterIsInstance<ScenarioStep.Assert>()
        .single { source == null || it.source == source }
    private val method = definition.scenario.steps.filterIsInstance<ScenarioStep.SendRequest>()
        .single { it.saveAs == assertion.source }.method

    init {
        require(assertion.checks.isNotEmpty()) { "Oracle controls need at least one check in $file" }
        context.registerBuiltInVariables()
        definition.scenario.workspace?.files.orEmpty().forEach { (path, text) ->
            context.stripCursorMarkers(context.workspace.resolveUri(path), text)
        }
    }

    fun accepts(response: Any?) {
        val node = context.normalizeResponse(method, JsonBridge.wrapJavaObject(response))
        assertion.checks.forEach { check ->
            assertThat(context.evaluateCheck(node, check)).describedAs(check.jsonPath).isTrue()
        }
    }

    fun rejects(response: Any?) {
        val node = context.normalizeResponse(method, JsonBridge.wrapJavaObject(response))
        assertThat(assertion.checks.all { context.evaluateCheck(node, it, quiet = true) })
            .describedAs("The YAML oracle must reject the mutant response: %s", node)
            .isFalse()
    }

    fun classifyFailure(response: Any?): XfailDecision {
        val failure = try {
            accepts(response)
            null
        } catch (failure: AssertionError) {
            failure
        }
        return XfailOutcome.classify(requireNotNull(definition.scenario.xfail), definition.source, failure)
    }
}
