package com.github.albertocavalcante.groovylsp.providers.codelens

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import com.github.albertocavalcante.groovytesting.registry.TestFrameworkRegistry
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provides CodeLens for test methods across all registered test frameworks.
 *
 * Shows "▶ Run Test" and "🐛 Debug Test" buttons above each test method.
 *
 * Uses [TestFrameworkRegistry] to detect test classes and extract test items,
 * supporting Spock, JUnit 5, JUnit 4, TestNG, and any other registered frameworks.
 */
class TestCodeLensProvider(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(TestCodeLensProvider::class.java)

    /**
     * Generate CodeLens for all test methods in a file.
     */
    fun provideCodeLenses(uri: URI): List<CodeLens> {
        val parseResult = compilationService.getParseResult(uri) ?: return emptyList()
        val ast = parseResult.ast ?: return emptyList()
        val classLoader = parseResult.compilationUnit.classLoader

        val codeLenses = mutableListOf<CodeLens>()

        for (classNode in ast.classes) {
            // Use registry to find applicable detector and extract tests
            val tests = TestFrameworkRegistry.extractTests(classNode, ast, classLoader)
            if (tests.isEmpty()) continue

            // Generate CodeLens for each test method (not the class itself)
            for (test in tests.filter { it.kind == TestItemKind.METHOD }) {
                val line = (test.line - 1).coerceAtLeast(0)
                val range = Range(Position(line, 0), Position(line, 0))

                // Use parent (class name) as suite if available, otherwise extract from id
                val suite = test.parent ?: test.id.substringBeforeLast(".")

                codeLenses.add(
                    CodeLens(
                        range,
                        Command(
                            "▶ Run Test",
                            // TODO(#624): Move execution to server-side via BSP commands
                            "groovy.test.run",
                            listOf(
                                mapOf(
                                    "uri" to uri.toString(),
                                    "suite" to suite,
                                    "test" to test.name,
                                    "framework" to test.framework.name,
                                ),
                            ),
                        ),
                        null,
                    ),
                )

                codeLenses.add(
                    CodeLens(
                        range,
                        Command(
                            "🐛 Debug Test",
                            "groovy.test.debug",
                            listOf(
                                mapOf(
                                    "uri" to uri.toString(),
                                    "suite" to suite,
                                    "test" to test.name,
                                    "debug" to true,
                                    "framework" to test.framework.name,
                                ),
                            ),
                        ),
                        null,
                    ),
                )
            }
        }

        logger.debug("Generated {} CodeLenses for {}", codeLenses.size, uri)
        return codeLenses
    }
}
