package com.github.albertocavalcante.groovylsp.e2e

/**
 * Abstraction for scenario parsing, enabling easy migration between serialization engines.
 *
 * Current implementation: [JacksonScenarioParser] (Jackson + YAML)
 *
 * TODO: Migrate to kotlinx.serialization when official YAML support is added.
 *       Track: https://github.com/Kotlin/kotlinx.serialization/issues/1836
 *       When available:
 *       1. Create `KotlinxScenarioParser` implementing this interface
 *       2. Add @Serializable annotations to data classes in Scenario.kt
 *       3. Swap implementation in ScenarioLoader
 *       4. Remove Jackson dependency from e2eTest
 */
interface ScenarioParser {
    /**
     * Parse a YAML string into a [ScenarioDefinition].
     *
     * @param yaml The raw YAML content
     * @return Parsed scenario definition
     * @throws ScenarioParseException if parsing fails
     */
    fun parseScenarioDefinition(yaml: String): ScenarioDefinition
}

/**
 * Exception thrown when scenario parsing fails.
 */
class ScenarioParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
