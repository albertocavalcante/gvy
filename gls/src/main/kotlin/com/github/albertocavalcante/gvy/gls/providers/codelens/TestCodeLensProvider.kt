package com.github.albertocavalcante.gvy.gls.providers.codelens

import com.github.albertocavalcante.groovytesting.api.TestItemKind
import com.github.albertocavalcante.groovytesting.registry.TestFrameworkRegistry
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

/**
 * Parameters for creating a test CodeLens.
 */
private data class CodeLensParams(
    val range: Range,
    val title: String,
    val command: String,
    val uri: URI,
    val suite: String,
    val test: String,
    val framework: String? = null,
    val debug: Boolean = false,
)

/**
 * Provides CodeLens for test methods across all registered test frameworks.
 *
 * Shows CodeLens buttons above test classes and methods:
 * - Class-level: "$(play) Run All", "$(debug-alt) Debug All", "$(beaker) Coverage"
 * - Method-level: "$(play) Run", "$(debug-alt) Debug", "$(beaker) Coverage"
 *
 * Uses [TestFrameworkRegistry] to detect test classes and extract test items,
 * supporting Spock, JUnit 5, JUnit 4, TestNG, and any other registered frameworks.
 */
class TestCodeLensProvider(
    private val compilationService: GroovyCompilationService,
    private val registry: TestFrameworkRegistry = TestFrameworkRegistry.default,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Generate CodeLens for all test methods in a file.
     */
    fun provideCodeLenses(uri: URI): List<CodeLens> {
        val parseResult = compilationService.getParseResult(uri) ?: return emptyList()
        val ast = parseResult.ast ?: return emptyList()
        val classLoader = parseResult.compilationUnit.classLoader

        val codeLenses = mutableListOf<CodeLens>()

        for (classNode in ast.classes) {
            val tests = registry.extractTests(classNode, ast, classLoader)
            if (tests.isEmpty()) continue

            addClassLevelCodeLenses(tests, uri, codeLenses)
            addMethodLevelCodeLenses(tests, uri, codeLenses)
        }

        logger.debug { "Generated ${codeLenses.size} CodeLenses for $uri" }
        return codeLenses
    }

    /**
     * Add class-level CodeLens (Run All, Debug All, Coverage).
     */
    private fun addClassLevelCodeLenses(
        tests: List<com.github.albertocavalcante.groovytesting.api.TestItem>,
        uri: URI,
        codeLenses: MutableList<CodeLens>,
    ) {
        for (classTest in tests.filter { it.kind == TestItemKind.CLASS }) {
            val line = (classTest.line - 1).coerceAtLeast(0)
            val range = Range(Position(line, 0), Position(line, 0))

            // Run All Tests
            codeLenses.add(
                createCodeLens(
                    CodeLensParams(
                        range = range,
                        title = "$(play) Run All",
                        command = "groovy.test.run",
                        uri = uri,
                        suite = classTest.id,
                        test = "*",
                    ),
                ),
            )

            // Debug All Tests
            codeLenses.add(
                createCodeLens(
                    CodeLensParams(
                        range = range,
                        title = "$(debug-alt) Debug All",
                        command = "groovy.test.debug",
                        uri = uri,
                        suite = classTest.id,
                        test = "*",
                        debug = true,
                    ),
                ),
            )

            // Coverage
            codeLenses.add(
                createCodeLens(
                    CodeLensParams(
                        range = range,
                        title = "$(beaker) Coverage",
                        command = "groovy.test.runWithCoverage",
                        uri = uri,
                        suite = classTest.id,
                        test = "*",
                    ),
                ),
            )
        }
    }

    /**
     * Add method-level CodeLens (Run, Debug, Coverage).
     */
    private fun addMethodLevelCodeLenses(
        tests: List<com.github.albertocavalcante.groovytesting.api.TestItem>,
        uri: URI,
        codeLenses: MutableList<CodeLens>,
    ) {
        for (test in tests.filter { it.kind == TestItemKind.METHOD }) {
            val line = (test.line - 1).coerceAtLeast(0)
            val range = Range(Position(line, 0), Position(line, 0))
            val suite = test.parent ?: test.id.substringBeforeLast(".")

            codeLenses.add(
                createCodeLens(
                    CodeLensParams(
                        range = range,
                        title = "$(play) Run",
                        command = "groovy.test.run",
                        uri = uri,
                        suite = suite,
                        test = test.name,
                        framework = test.framework.name,
                    ),
                ),
            )

            codeLenses.add(
                createCodeLens(
                    CodeLensParams(
                        range = range,
                        title = "$(debug-alt) Debug",
                        command = "groovy.test.debug",
                        uri = uri,
                        suite = suite,
                        test = test.name,
                        framework = test.framework.name,
                        debug = true,
                    ),
                ),
            )

            codeLenses.add(
                createCodeLens(
                    CodeLensParams(
                        range = range,
                        title = "$(beaker) Coverage",
                        command = "groovy.test.runWithCoverage",
                        uri = uri,
                        suite = suite,
                        test = test.name,
                        framework = test.framework.name,
                    ),
                ),
            )
        }
    }

    /**
     * Create a CodeLens with the given parameters.
     */
    private fun createCodeLens(params: CodeLensParams): CodeLens {
        val args = mutableMapOf<String, Any>(
            "uri" to params.uri.toString(),
            "suite" to params.suite,
            "test" to params.test,
            "debug" to params.debug,
        )
        if (params.framework != null) {
            args["framework"] = params.framework
        }
        return CodeLens(params.range, Command(params.title, params.command, listOf(args)), null)
    }
}
