package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyspock.SpockFeatureExtractor
import org.codehaus.groovy.ast.ClassNode
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Discovers Spock test classes and feature methods in a workspace.
 *
 * Used by the `groovy/discoverTests` LSP request to populate VS Code Test Explorer.
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

            // Check each class individually to handle mixed files correctly
            for (classNode in ast.classes) {
                // Skip non-Spock classes (check class hierarchy, not just file)
                if (!isSpockSpecClass(classNode)) continue

                val features = SpockFeatureExtractor.extractFeatures(classNode)

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

    /**
     * Check if a class extends spock.lang.Specification (directly or indirectly).
     */
    private fun isSpockSpecClass(classNode: ClassNode): Boolean {
        // Check super class chain for Specification
        var superClass = classNode.superClass
        while (superClass != null) {
            if (superClass.name == "spock.lang.Specification") {
                return true
            }
            // Also check simple name for cases where import resolves it
            if (superClass.nameWithoutPackage == "Specification") {
                return true
            }
            superClass = superClass.superClass
        }
        return false
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
