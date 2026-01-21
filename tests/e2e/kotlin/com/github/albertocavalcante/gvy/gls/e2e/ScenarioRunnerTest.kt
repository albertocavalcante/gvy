package com.github.albertocavalcante.gvy.gls.e2e

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import java.nio.file.Path
import kotlin.io.path.exists

class ScenarioRunnerTest {
    private val loader = ScenarioLoader()
    private val sessionFactory = LanguageServerSessionFactory()
    private val executor = ScenarioExecutor(sessionFactory)

    @TestFactory
    fun endToEndScenarios(): List<DynamicTest> {
        val scenarioDir = resolveScenarioDirectory()
        val definitions = loader.loadAll(scenarioDir).filter { definition ->
            val filter = System.getProperty("groovy.lsp.e2e.filter")
            filter.isNullOrBlank() || definition.scenario.name.contains(filter)
        }

        if (definitions.isEmpty()) {
            fail("No scenarios discovered under $scenarioDir")
        }

        return definitions.map { definition ->
            DynamicTest.dynamicTest(definition.scenario.name) {
                executor.execute(definition)
            }
        }
    }

    private fun resolveScenarioDirectory(): Path {
        val raw = resolveScenarioDir()  // Use shared helper that supports both Gradle and Bazel
        val path = Path.of(raw)
        require(path.exists()) {
            "Scenario directory '$raw' does not exist (resolved to $path)"
        }
        return path
    }
}
