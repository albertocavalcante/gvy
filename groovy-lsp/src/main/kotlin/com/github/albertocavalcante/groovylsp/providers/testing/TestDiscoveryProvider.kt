package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import com.github.albertocavalcante.groovytesting.registry.TestFrameworkRegistry
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

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
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun discoverTests(workspaceUri: String): List<TestSuite> {
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
        val groovyFiles = sourceUris.filter { it.path.endsWith(".groovy", ignoreCase = true) }
        logger.info("Found {} source URIs, {} are Groovy files", sourceUris.size, groovyFiles.size)

        for (uri in groovyFiles) {
            // Get parsed result for this file - use getValidParseResult to handle stale Script nodes
            val parseResult: ParseResult =
                compilationService.getValidParseResult(uri) ?: run {
                    // File not in cache - compile it on demand
                    // This can happen when workspace is indexed but files aren't opened in editor yet
                    logger.info("File not cached, compiling on demand: {}", uri)
                    val content = try {
                        Files.readString(Path.of(uri))
                    } catch (e: Exception) {
                        logger.warn("Failed to read file for test discovery: {} - {}", uri, e.message)
                        return@run null
                    }
                    // Compile the file - this populates the cache
                    compilationService.compile(uri, content)
                    // Now fetch the ParseResult from cache
                    compilationService.getValidParseResult(uri)
                } ?: continue // Skip if still null after compilation

            val ast = parseResult.ast
            if (ast == null) {
                logger.info("No AST for: {} - compilation may have failed", uri)
                continue
            }
            val classLoader = parseResult.compilationUnit.classLoader
            logger.info("Processing {} - found {} classes in AST", uri.path.substringAfterLast('/'), ast.classes.size)

            // Check each class individually to handle mixed files correctly
            logger.debug(
                "Classes in AST for $uri: ${
                    ast.classes.map {
                        "${it.name} (super=${it.superClass.name}) methods=[${it.methods.joinToString { m -> m.name }}]"
                    }
                }",
            )
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
        logger.info(
            "Test discovery complete: found {} test suites with {} total tests",
            testSuites.size,
            testSuites.sumOf { it.tests.size },
        )
        return testSuites
    }

    companion object {
        /**
         * Parse a workspace URI string to a URI object.
         * Handles both file:// URIs and plain paths.
         */
        @Suppress("SwallowedException")
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
