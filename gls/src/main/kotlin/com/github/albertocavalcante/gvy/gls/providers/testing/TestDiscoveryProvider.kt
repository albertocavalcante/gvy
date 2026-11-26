// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.testing

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.testing.api.TestItemKind
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.nativeapi.ParseResult
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

/**
 * Discovers test classes and methods across all registered test frameworks.
 *
 * Used by the `groovy/discoverTests` LSP request to populate VS Code Test Explorer.
 *
 * Uses [TestFrameworkRegistry] to detect tests from Spock, JUnit 5, JUnit 4, TestNG,
 * and any other registered frameworks.
 */
class TestDiscoveryProvider(
    private val compilationService: GroovyCompilationService,
    private val registry: TestFrameworkRegistry = TestFrameworkRegistry.default,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Discover all test suites in the workspace.
     *
     * @param workspaceUri The workspace root URI (used to identify the target workspace)
     * @return List of [TestSuite] containing discovered tests
     */
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun discoverTests(workspaceUri: String): List<TestSuite> {
        logger.info { "Discovering tests in workspace: $workspaceUri" }

        val testSuites = mutableListOf<TestSuite>()

        // Validate workspace URI for potential future filtering
        if (parseWorkspaceUri(workspaceUri) == null) {
            logger.warn { "Invalid workspace URI: $workspaceUri" }
            return emptyList()
        }

        // Get all workspace source URIs
        val sourceUris = compilationService.workspaceManager.getWorkspaceSourceUris()
        val groovyFiles = sourceUris.filter { it.path.endsWith(".groovy", ignoreCase = true) }
        logger.info { "Found ${sourceUris.size} source URIs, ${groovyFiles.size} are Groovy files" }

        for (uri in groovyFiles) {
            testSuites.addAll(discoverTestsForFile(uri))
        }
        logger.info {
            "Test discovery complete: found ${testSuites.size} test suites with ${testSuites.sumOf {
                it.tests.size
            }} total tests"
        }
        return testSuites
    }

    private suspend fun discoverTestsForFile(uri: URI): List<TestSuite> =
        compilationService.queryUncachedTestDiscovery(uri) { parsed -> extractSuites(uri, parsed) }.orEmpty()

    private fun extractSuites(uri: URI, parseResult: ParseResult): List<TestSuite> {
        val ast = parseResult.ast
        if (ast == null) {
            logger.info { "No AST for: $uri - compilation may have failed" }
            return emptyList()
        }

        val classLoader = parseResult.compilationUnit.classLoader
        logger.info { "Processing ${uri.path.substringAfterLast('/')} - found ${ast.classes.size} classes in AST" }

        // Check each class individually to handle mixed files correctly
        logger.debug {
            "Classes in AST for $uri: ${
                ast.classes.map {
                    "${it.name} (super=${it.superClass.name}) methods=[${it.methods.joinToString { m -> m.name }}]"
                }
            }"
        }

        return ast.classes.mapNotNull { classNode ->
            val testItems = registry.extractTests(classNode, ast, classLoader)
            if (testItems.isEmpty()) return@mapNotNull null

            // Extract class line from the CLASS test item
            val classItem = testItems.find { it.kind == TestItemKind.CLASS }
            val classLine = classItem?.line ?: classNode.lineNumber.coerceAtLeast(1)

            val tests = testItems
                .filter { it.kind == TestItemKind.METHOD }
                .map { Test(test = it.name, line = it.line) }
            if (tests.isEmpty()) return@mapNotNull null

            val framework = testItems.firstOrNull()?.framework
            logger.debug {
                "Found $framework test suite: ${classNode.name} at line $classLine with ${tests.size} tests"
            }

            TestSuite(
                uri = uri.toString(),
                suite = classNode.name,
                line = classLine,
                tests = tests,
                inheritsTests = classNode.superClass.name !in NON_INHERITING_SUPERCLASSES,
            )
        }
    }

    companion object {
        /** Superclasses that contribute no features of their own. */
        private val NON_INHERITING_SUPERCLASSES = setOf(
            "spock.lang.Specification",
            // Unresolved (below the resolution phase) the base keeps its source spelling.
            "Specification",
            "java.lang.Object",
        )

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
