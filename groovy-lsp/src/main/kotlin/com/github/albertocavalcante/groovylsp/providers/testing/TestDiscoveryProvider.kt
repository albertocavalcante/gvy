package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import com.github.albertocavalcante.groovytesting.registry.TestFrameworkRegistry
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
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

        // Validate workspace URI for potential future filtering
        if (parseWorkspaceUri(workspaceUri) == null) {
            logger.warn("Invalid workspace URI: $workspaceUri")
            return emptyList()
        }

        // Get all workspace source URIs
        val sourceUris = compilationService.workspaceManager.getWorkspaceSourceUris()
        val groovyFiles = sourceUris.filter { it.path.endsWith(".groovy", ignoreCase = true) }
        logger.info("Found {} source URIs, {} are Groovy files", sourceUris.size, groovyFiles.size)

        for (uri in groovyFiles) {
            testSuites.addAll(discoverTestsForFile(uri))
        }
        logger.info(
            "Test discovery complete: found {} test suites with {} total tests",
            testSuites.size,
            testSuites.sumOf { it.tests.size },
        )
        return testSuites
    }

    private suspend fun discoverTestsForFile(uri: URI): List<TestSuite> {
        // Get parsed result for this file - use getValidParseResult to handle stale Script nodes
        val parseResult = getOrCompileParseResult(uri) ?: return emptyList()
        val ast = parseResult.ast
        if (ast == null) {
            logger.info("No AST for: {} - compilation may have failed", uri)
            return emptyList()
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

        return ast.classes.mapNotNull { classNode ->
            val testItems = TestFrameworkRegistry.extractTests(classNode, ast, classLoader)
            if (testItems.isEmpty()) return@mapNotNull null

            val tests = testItems
                .filter { it.kind == TestItemKind.METHOD }
                .map { Test(test = it.name, line = it.line) }
            if (tests.isEmpty()) return@mapNotNull null

            val framework = testItems.firstOrNull()?.framework
            logger.debug(
                "Found {} test suite: {} with {} tests",
                framework,
                classNode.name,
                tests.size,
            )

            TestSuite(
                uri = uri.toString(),
                suite = classNode.name,
                tests = tests,
            )
        }
    }

    private suspend fun getOrCompileParseResult(uri: URI): ParseResult? {
        val cached = compilationService.getValidParseResult(uri)
        if (cached != null) return cached

        // File not in cache - compile it on demand.
        // This can happen when workspace is indexed but files aren't opened in editor yet.
        logger.info("File not cached, compiling on demand: {}", uri)
        val content = readFileContent(uri) ?: return null
        compilationService.compile(uri, content)
        return compilationService.getValidParseResult(uri)
    }

    private fun readFileContent(uri: URI): String? = try {
        Files.readString(Path.of(uri))
    } catch (e: InvalidPathException) {
        logger.warn("Failed to read file for test discovery: {} - {}", uri, e.message)
        null
    } catch (e: IllegalArgumentException) {
        logger.warn("Failed to read file for test discovery: {} - {}", uri, e.message)
        null
    } catch (e: SecurityException) {
        logger.warn("Failed to read file for test discovery: {} - {}", uri, e.message)
        null
    } catch (e: IOException) {
        logger.warn("Failed to read file for test discovery: {} - {}", uri, e.message)
        null
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
