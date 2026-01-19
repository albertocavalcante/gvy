package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.UnusedImportDetector
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.SemanticTokens
import java.net.URI

/**
 * Handler for semantic tokens functionality.
 *
 * Extracted from GroovyTextDocumentService to reduce class size and improve maintainability.
 * This handler coordinates semantic token generation by:
 * - Ensuring compilation is complete
 * - Detecting Jenkins files
 * - Detecting unused imports
 * - Generating Groovy and Jenkins semantic tokens
 * - Combining and encoding tokens for LSP transmission
 *
 * @see GroovySemanticTokenProvider
 * @see JenkinsSemanticTokenProvider
 * @see SemanticTokensEncoder
 */
class SemanticTokensHandler(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Generate semantic tokens for a document.
     *
     * @param uri Document URI
     * @return Encoded semantic tokens for LSP transmission. Returns an empty token set if compilation fails or no AST is available.
     */
    @Suppress("ReturnCount") // Early returns for error cases improve readability
    suspend fun getSemanticTokens(uri: URI): SemanticTokens {
        @Suppress("TooGenericExceptionCaught") // Catch all exceptions to prevent LSP crashes
        try {
            // Ensure document is compiled
            val compilationResult = compilationService.ensureCompiled(uri)
            if (compilationResult == null) {
                logger.warn { "Document $uri not compiled, returning empty tokens" }
                return SemanticTokens(emptyList())
            }

            // Get AST model
            val astModel = compilationService.getAstModel(uri)
            if (astModel == null) {
                logger.warn { "No AST model available for $uri, returning empty tokens" }
                return SemanticTokens(emptyList())
            }

            // Check if this is a Jenkins file
            val jenkinsCapabilities = compilationService.workspaceManager.getJenkinsCapabilities()
            val isJenkinsFile = jenkinsCapabilities?.isJenkinsFile(uri) ?: false

            // Get vars/ global variable names for semantic highlighting
            val varsNames = jenkinsCapabilities?.getGlobalVariables()
                ?.map { it.name }
                ?.toSet()
                ?: emptySet()

            // Detect unused imports for dimming (semantic token modifier)
            val moduleNode = compilationService.getAst(uri) as? ModuleNode
            val unusedImports = moduleNode?.let {
                UnusedImportDetector.detectUnusedImports(it).toSet()
            } ?: emptySet()

            // Get source text for accurate method name offset calculation
            val sourceText = compilationResult.sourceText ?: documentProvider.get(uri)

            // Get general Groovy semantic tokens for ALL files
            val groovyTokens = GroovySemanticTokenProvider.getSemanticTokens(
                astModel,
                uri,
                unusedImports = unusedImports,
                moduleNode = moduleNode,
                sourceText = sourceText,
            )

            // Get Jenkins-specific tokens (built-in blocks + vars/ globals) only for Jenkins files
            val jenkinsTokens = if (isJenkinsFile) {
                JenkinsSemanticTokenProvider.getSemanticTokens(
                    astModel,
                    uri,
                    isJenkinsFile,
                    varsNames,
                )
            } else {
                emptyList()
            }

            // Combine all tokens and encode using SemanticTokensEncoder
            val allTokens = SemanticTokensEncoder.combine(groovyTokens, jenkinsTokens)
                .sortedWith(
                    compareBy<JenkinsSemanticTokenProvider.SemanticToken> { it.line }
                        .thenBy { it.startChar }
                        .thenBy { it.length }
                        .thenBy { it.tokenType }
                        .thenBy { it.tokenModifiers },
                )
            val encodedData = SemanticTokensEncoder.encode(allTokens)

            logger.debug { "Returning ${allTokens.size} semantic tokens (${encodedData.size} integers)" }
            return SemanticTokens(encodedData)
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate semantic tokens for $uri" }
            return SemanticTokens(emptyList())
        }
    }
}
