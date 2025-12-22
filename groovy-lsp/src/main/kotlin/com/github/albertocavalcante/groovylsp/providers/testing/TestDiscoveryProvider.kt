package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import com.github.albertocavalcante.groovytesting.registry.TestFrameworkRegistry
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Discovers test classes and methods across all registered test frameworks.
 *
 * Used by the `groovy/discoverTests` LSP request to populate VS Code Test Explorer.
 *
 * Uses [TestFrameworkRegistry] to detect tests from Spock, JUnit 5, JUnit 4, TestNG,
 * and any other registered frameworks.
 */
class TestDiscoveryProvider(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(TestDiscoveryProvider::class.java)

    /**
     * Discover all test suites in the workspace.
     *
     * @param workspaceUri The workspace root URI (used to identify the target workspace)
     * @return List of [TestSuite] containing discovered tests
     */
    fun discoverTests(workspaceUri: String): List<TestSuite> {
        logger.info("Discovering tests in workspace: $workspaceUri")

        val testSuites = mutableListOf<TestSuite>()

        // Parse workspace URI for potential future filtering
        val requestedUri = parseWorkspaceUri(workspaceUri)
        if (requestedUri == null) {
            logger.warn("Invalid workspace URI: $workspaceUri")
            return emptyList()
        }

        // Get all workspace source URIs
        val sourceUris = compilationService.workspaceManager.getWorkspaceSourceUris()

        for (uri in sourceUris) {
            // Skip non-Groovy files
            if (!uri.path.endsWith(".groovy", ignoreCase = true)) continue

            // Get parsed result for this file
            val parseResult = compilationService.getParseResult(uri) ?: continue
            val ast = parseResult.ast ?: continue
            val classLoader = parseResult.compilationUnit.classLoader

            // Check each class individually to handle mixed files correctly
            for (classNode in ast.classes) {
                // Use registry to detect and extract tests
                val testItems = TestFrameworkRegistry.extractTests(classNode, ast, classLoader)
                if (testItems.isEmpty()) continue

                // Convert TestItems to Test DTOs (only methods, not classes)
                val tests = testItems
                    .filter { it.kind == TestItemKind.METHOD }
                    .map { Test(test = it.name, line = it.line) }

                if (tests.isNotEmpty()) {
                    // Get framework from first item for logging
                    val framework = testItems.firstOrNull()?.framework

                    testSuites.add(
                        TestSuite(
                            uri = uri.toString(),
                            suite = classNode.name,
                            tests = tests,
                        ),
                    )

                    logger.debug(
                        "Found {} test suite: {} with {} tests",
                        framework,
                        classNode.name,
                        tests.size,
                    )
                }
            }
        }

        logger.info("Discovered {} test suites", testSuites.size)
        return testSuites
    }

    companion object {
        /**
         * Parse a workspace URI string to a URI object.
         * Handles both file:// URIs and plain paths.
         */
        fun parseWorkspaceUri(workspaceUri: String): URI? = try {
            if (workspaceUri.startsWith("file://")) {
                URI.create(workspaceUri)
            } else {
                URI.create("file://$workspaceUri")
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
