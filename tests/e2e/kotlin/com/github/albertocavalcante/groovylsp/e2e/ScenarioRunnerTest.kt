package com.github.albertocavalcante.groovylsp.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import java.nio.file.Path
import kotlin.io.path.exists

class ScenarioRunnerTest {
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val loader = ScenarioLoader(mapper)
    private val sessionFactory = LanguageServerSessionFactory(mapper)
    private val executor = ScenarioExecutor(sessionFactory, mapper)

    @TestFactory
    fun endToEndScenarios(): List<DynamicTest> {
        val scenarioDir = resolveScenarioDirectory()
        val definitions = loader.loadAll(scenarioDir)

        val filter = System.getProperty("groovy.lsp.e2e.filter")
        val filteredDefinitions = if (filter.isNullOrBlank()) {
            definitions
        } else {
            definitions.filter { it.scenario.name.contains(filter, ignoreCase = true) }
        }

        if (filteredDefinitions.isEmpty()) {
            if (filter != null) {
                fail("No scenarios matched filter '$filter'")
            } else {
                fail("No scenarios discovered under $scenarioDir")
            }
        }

        return filteredDefinitions.map { definition ->
            DynamicTest.dynamicTest(definition.scenario.name) {
                executor.execute(definition)
            }
        }
    }

    private fun resolveScenarioDirectory(): Path {
        val raw = System.getProperty("groovy.lsp.e2e.scenarioDir")
            ?: fail("Missing system property 'groovy.lsp.e2e.scenarioDir'")

        val path = Path.of(raw)
        require(path.exists()) {
            "Scenario directory '$raw' does not exist (resolved to $path)"
        }
        return path
    }
}
