package com.github.albertocavalcante.groovylsp.providers.semantictokens

import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsBlockMetadata
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provides Jenkins-specific semantic tokens for enhanced syntax highlighting.
 *
 * Jenkins DSL blocks like `withCredentials`, `stage`, `node`, `parallel` get
 * special token types for distinct visual highlighting in IDEs.
 *
 * LSP Semantic Token Types used:
 * - "macro" (index 14) - Jenkins DSL structural blocks (pipeline, stages, steps, etc.)
 * - "decorator" (index 22) - Wrapper blocks (withCredentials, timeout, retry, etc.)
 *
 * NOTE: Block categorization logic lives in groovy-jenkins module (JenkinsBlockMetadata).
 * This provider is thin presentation logic that maps domain data to LSP format.
 *
 * TODO: Add support for general Groovy syntax highlighting (keywords, operators, etc.)
 * TODO: Consider caching token data if profiling shows performance issues
 */
object JenkinsSemanticTokenProvider {

    private val logger = LoggerFactory.getLogger(JenkinsSemanticTokenProvider::class.java)

    /**
     * Semantic token representation for Jenkins blocks.
     *
     * @param line 0-based line number
     * @param startChar 0-based character offset
     * @param length Token length in characters
     * @param tokenType Token type index (from legend)
     * @param tokenModifiers Token modifiers as bitfield
     */
    data class SemanticToken(
        val line: Int,
        val startChar: Int,
        val length: Int,
        val tokenType: Int,
        val tokenModifiers: Int = 0,
    )

    /**
     * Token type indices matching LSP semantic token legend.
     * These MUST match the order in SemanticTokensLegend.tokenTypes.
     *
     * NOTE: These are 0-based indices into the token types array:
     * [0=namespace, 1=type, 2=class, ..., 12=function, 13=method, 14=macro, ..., 22=decorator]
     */
    object TokenTypes {
        const val FUNCTION = 12 // Standard function token
        const val METHOD = 13 // Standard method token
        const val MACRO = 14 // For pipeline structural elements
        const val DECORATOR = 22 // For wrapper blocks
    }

    /**
     * Generate semantic tokens for Jenkins-specific constructs.
     *
     * @param astModel Parsed AST model
     * @param uri Document URI
     * @param isJenkinsFile Whether this is a Jenkins file
     * @return List of semantic tokens for Jenkins blocks
     */
    fun getSemanticTokens(astModel: GroovyAstModel, uri: URI, isJenkinsFile: Boolean): List<SemanticToken> {
        if (!isJenkinsFile) {
            return emptyList()
        }

        val tokens = mutableListOf<SemanticToken>()

        try {
            val allNodes = astModel.getAllNodes()

            allNodes.filterIsInstance<MethodCallExpression>().forEach { methodCall ->
                val methodName = methodCall.methodAsString ?: return@forEach
                val tokenType = getTokenTypeForMethod(methodName)

                getTokenTypeForMethod(methodName)?.let { tokenType ->
                    // Get the method name position (not the whole expression)
                    val methodNode = methodCall.method
                    if (methodNode.lineNumber > 0) {
                        tokens.add(
                            SemanticToken(
                                line = methodNode.lineNumber - 1,
                                startChar = methodNode.columnNumber - 1,
                                length = methodName.length,
                                tokenType = tokenType,
                            ),
                        )
                    }
                }
            }

            logger.debug("Generated {} Jenkins semantic tokens for {}", tokens.size, uri)
        } catch (e: Exception) {
            logger.error("Failed to generate Jenkins semantic tokens for {}", uri, e)
        }

        return tokens
    }

    /**
     * Determine the semantic token type for a Jenkins method.
     */
    private fun getTokenTypeForMethod(methodName: String): Int? {
        val category = JenkinsBlockMetadata.getCategoryFor(methodName) ?: return null

        return when (category) {
            JenkinsBlockMetadata.BlockCategory.PIPELINE_STRUCTURE -> TokenTypes.MACRO
            JenkinsBlockMetadata.BlockCategory.WRAPPER -> TokenTypes.DECORATOR
            JenkinsBlockMetadata.BlockCategory.CREDENTIAL -> TokenTypes.DECORATOR
            JenkinsBlockMetadata.BlockCategory.POST_CONDITION -> TokenTypes.MACRO
            JenkinsBlockMetadata.BlockCategory.DECLARATIVE_OPTION -> TokenTypes.DECORATOR
            JenkinsBlockMetadata.BlockCategory.AGENT_TYPE -> TokenTypes.MACRO
        }
    }
}
