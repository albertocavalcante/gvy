package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyspock.SpockDetector
import com.github.albertocavalcante.groovyspock.SpockFeatureExtractor
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Discovers Spock test classes and feature methods in a workspace.
 *
 * Used by the `groovy/discoverTests` LSP request to populate VS Code Test Explorer.
 *
 * NOTE: This provider relies on [GroovyCompilationService.getParseResult] which
 * retrieves results from the [CompilationCache]. Files must be compiled (e.g., via
 * didOpen or workspace indexing) before they appear in discovery results.
 * Consider calling [GroovyCompilationService.indexWorkspace] for full coverage
 * or triggering compilation for individual files before discovery.
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
        // (currently single-workspace, but multi-workspace support may come later)
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

            val specClassNode = SpockDetector.getSpecificationClassNode(parseResult)

            // Check each class individually to handle mixed files correctly
            for (classNode in ast.classes) {
                // Skip non-Spock classes (check class hierarchy, not just file)
                if (!SpockDetector.isSpockSpec(classNode, ast, specClassNode)) continue

                val features = SpockFeatureExtractor.extractFeatures(classNode)
                // ... (rest of the logic remains same)
                if (features.isNotEmpty()) {
                    val tests = features.map { feature ->
                        Test(test = feature.name, line = feature.line)
                    }

                    testSuites.add(
                        TestSuite(
                            uri = uri.toString(),
                            suite = classNode.name,
                            tests = tests,
                        ),
                    )

                    logger.debug(
                        "Found test suite: {} with {} tests",
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
