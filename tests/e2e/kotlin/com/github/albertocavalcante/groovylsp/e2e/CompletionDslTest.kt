package com.github.albertocavalcante.groovylsp.e2e

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/**
 * Example E2E test using the Kotlin DSL for scenario definition.
 *
 * This demonstrates how to write E2E tests programmatically instead of using YAML files.
 * The DSL provides type safety, IDE support, and better refactoring capabilities.
 */
class CompletionDslTest {

    private val sessionFactory = LanguageServerSessionFactory()
    private val executor = ScenarioExecutor(sessionFactory)

    @Test
    fun `completion for println using DSL`() {
        val scenarioDefinition = scenario {
            name("completion-dsl-test")
            description("Test completion for println using Kotlin DSL")
            workspace("completion-basic")

            // Initialize the LSP server
            initialize()
            initialized()

            // Wait for server to be ready
            waitForServerReady()

            // Open a document with incomplete code
            openDocument(
                path = "src/Main.groovy",
                text = """
                    class Main {
                        def run() {
                            prin
                        }
                    }
                """.trimIndent(),
            )

            // Request completion at the position after "prin"
            request("textDocument/completion", saveAs = "completion") {
                position(path = "src/Main.groovy", line = 2, character = 16)
                extract("completion.items", "$.items")
            }

            // Assert that completions are not empty
            assertResponse {
                jsonPath("$.items") { notEmpty() }
            }

            // Clean shutdown
            shutdown()
            exit()
        }

        // Execute the scenario
        executor.execute(scenarioDefinition)
    }

    @Test
    fun `completion using high-level DSL method`() {
        val scenarioDefinition = scenario {
            name("completion-dsl-highlevel")
            description("Test completion using the high-level completion() method")
            workspace("completion-basic")

            initialize()
            initialized()
            waitForServerReady()

            openDocument(
                path = "src/Test.groovy",
                text = "class Test { def foo() { prin } }",
            )

            // Using the high-level completion method
            completion(path = "src/Test.groovy", line = 0, character = 29) {
                jsonPath("$.items") {
                    notEmpty()
                }
            }

            shutdown()
            exit()
        }

        executor.execute(scenarioDefinition)
    }

    @Test
    fun `document lifecycle using DSL`() {
        val scenarioDefinition = scenario {
            name("document-lifecycle-dsl")
            description("Test opening, changing, saving, and closing documents")
            workspace("completion-basic")

            initialize()
            initialized()
            waitForServerReady()

            // Open a document
            openDocument(
                path = "src/Lifecycle.groovy",
                text = "class Lifecycle { }",
            )

            // Change the document
            changeDocument(
                path = "src/Lifecycle.groovy",
                text = "class Lifecycle { def test() { } }",
                version = 2,
            )

            // Save the document
            saveDocument(path = "src/Lifecycle.groovy")

            // Close the document
            closeDocument(path = "src/Lifecycle.groovy")

            shutdown()
            exit()
        }

        executor.execute(scenarioDefinition)
    }

    @Test
    fun `custom assertions with multiple checks`() {
        val scenarioDefinition = scenario {
            name("custom-assertions-dsl")
            description("Demonstrate various assertion types in the DSL")
            workspace("completion-basic")

            initialize()
            initialized()
            waitForServerReady()

            openDocument(
                path = "src/Assertions.groovy",
                text = """
                    class Assertions {
                        def numbers = [1, 2, 3, 4, 5]
                        def name = "test"
                    }
                """.trimIndent(),
            )

            request("textDocument/documentSymbol", saveAs = "symbols") {
                param(
                    "textDocument",
                    buildJsonObject {
                        put("uri", "{{workspace.uri}}src/Assertions.groovy")
                    },
                )
            }

            // Demonstrate various expectation types
            assertResponse {
                // Check existence
                jsonPath("$") { exists() }

                // Check array is not empty
                jsonPath("$") { notEmpty() }
            }

            shutdown()
            exit()
        }

        executor.execute(scenarioDefinition)
    }
}
