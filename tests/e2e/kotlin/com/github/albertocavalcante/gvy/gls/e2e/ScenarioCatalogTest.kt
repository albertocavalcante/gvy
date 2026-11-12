package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/** Checks the whole scenario catalog, including scenarios the runner skips. */
class ScenarioCatalogTest {
    private val loader = ScenarioLoader()

    @Test
    fun `every scenario file parses, disabled ones included`() {
        val dir = Path.of(resolveScenarioDir())
        // load() throws with the file name when a scenario does not parse
        assertThat(loader.loadEvery(dir)).isNotEmpty()
    }

    @Test
    fun `all authored xfail matchers compile under RE2`() {
        val scenarios = loader.loadEvery(Path.of(resolveScenarioDir()))
        val patterns = scenarios.flatMap { definition ->
            val scenario = definition.scenario
            val top = listOfNotNull(scenario.xfail?.match)
            val nested = scenario.steps.flatMap { step ->
                val checks = when (step) {
                    is ScenarioStep.Assert -> step.checks
                    is ScenarioStep.WaitNotification -> step.checks
                    is ScenarioStep.Completion -> step.checks
                    is ScenarioStep.CodeAction -> step.checks
                    is ScenarioStep.Formatting -> step.checks
                    is ScenarioStep.Rename -> step.checks
                    is ScenarioStep.CliCommand -> step.checks
                    else -> emptyList()
                }
                checks.mapNotNull { it.xfail?.match }
            }
            top + nested
        }
        assertThat(patterns).isNotEmpty()
        patterns.forEach(XfailOutcome::compileMatch)
    }

    @Test
    fun `disabled scenarios state why in a disabled header`() {
        val dir = Path.of(resolveScenarioDir())
        val problems = loader.scenarioFiles(dir)
            .filter { loader.isDisabled(it) }
            .mapNotNull { file -> disabledHeaderProblem(file.readText())?.let { "${file.fileName}: $it" } }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `scenario names are unique`() {
        val names = loader.loadEvery(Path.of(resolveScenarioDir())).map { it.scenario.name }
        assertThat(names).doesNotHaveDuplicates()
    }

    @Test
    fun `disabled header needs a reason`() {
        assertThat(disabledHeaderProblem("# disabled: gvy#123 flaky on CI\nname: x\n")).isNull()
        assertThat(disabledHeaderProblem("# Some note\n# disabled: waits for #9\nname: x\n")).isNull()
        assertThat(disabledHeaderProblem("name: x\n")).isNotNull()
        assertThat(disabledHeaderProblem("# disabled:\nname: x\n")).isNotNull()
        assertThat(disabledHeaderProblem("# TODO(DEFERRED): old style\nname: x\n")).isNotNull()
        assertThat(disabledHeaderProblem("name: x\n# disabled: too late\n")).isNotNull()
    }

    @Test
    fun `loader skips underscore files but loadEvery does not`(@org.junit.jupiter.api.io.TempDir tmp: Path) {
        val body = "name: %s\nsteps: []\n"
        tmp.resolve("on.yaml").toFile().writeText(body.format("on"))
        tmp.resolve("_off.yaml").toFile().writeText("# disabled: test\n" + body.format("off"))
        assertThat(loader.loadAll(tmp).map { it.scenario.name }).containsExactly("on")
        assertThat(loader.loadEvery(tmp).map { it.scenario.name }).containsExactlyInAnyOrder("on", "off")
    }
}
