package com.github.albertocavalcante.groovylsp.providers.codelens

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyspock.SpockDetector
import com.github.albertocavalcante.groovyspock.SpockFeatureExtractor
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provides CodeLens for Spock test methods.
 *
 * Shows "▶ Run Test" and "🐛 Debug Test" buttons above each feature method.
 *
 * NOTE: This provider relies on the cached AST which may have been compiled to
 * CANONICALIZATION phase. Spock's AST transformations at this phase may have
 * renamed methods and stripped block labels. The [SpockFeatureExtractor] is
 * designed to work with early-phase AST (CONVERSION or SEMANTIC_ANALYSIS).
 * For best results, ensure files are compiled with an early phase before
 * requesting CodeLenses via [GroovyTextDocumentService.codeLens], which calls
 * [GroovyCompilationService.ensureCompiled].
 */
class TestCodeLensProvider(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(TestCodeLensProvider::class.java)

    /**
     * Generate CodeLens for all Spock feature methods in a file.
     */
    fun provideCodeLenses(uri: URI): List<CodeLens> {
        val parseResult = compilationService.getParseResult(uri) ?: return emptyList()
        val ast = parseResult.ast ?: return emptyList()

        val codeLenses = mutableListOf<CodeLens>()
        val specClassNode = SpockDetector.getSpecificationClassNode(parseResult)

        for (classNode in ast.classes) {
            if (!SpockDetector.isSpockSpec(classNode, ast, specClassNode)) continue

            val features = SpockFeatureExtractor.extractFeatures(classNode)
            // ... (rest of the logic remains same)
            for (feature in features) {
                val line = (feature.line - 1).coerceAtLeast(0)
                val range = Range(Position(line, 0), Position(line, 0))

                codeLenses.add(
                    CodeLens(
                        range,
                        Command(
                            "▶ Run Test",
                            "groovy.test.run",
                            listOf(
                                mapOf(
                                    "uri" to uri.toString(),
                                    "suite" to classNode.name,
                                    "test" to feature.name,
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
                                    "suite" to classNode.name,
                                    "test" to feature.name,
                                    "debug" to true,
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
