// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsBlockMetadata
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import java.net.URI
import java.util.concurrent.CancellationException

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

    private val logger = KotlinLogging.logger {}

    /**
     * Complete list of Semantic Token Types supported by this server.
     * This serves as the single source of truth for the LSP legend.
     */
    val LEGEND_TOKEN_TYPES = listOf(
        SemanticTokenTypes.Namespace, // 0
        SemanticTokenTypes.Type, // 1
        SemanticTokenTypes.Class, // 2
        SemanticTokenTypes.Enum, // 3
        SemanticTokenTypes.Interface, // 4
        SemanticTokenTypes.Struct, // 5
        SemanticTokenTypes.TypeParameter, // 6
        SemanticTokenTypes.Parameter, // 7
        SemanticTokenTypes.Variable, // 8
        SemanticTokenTypes.Property, // 9
        SemanticTokenTypes.EnumMember, // 10
        SemanticTokenTypes.Event, // 11
        SemanticTokenTypes.Function, // 12
        SemanticTokenTypes.Method, // 13
        SemanticTokenTypes.Macro, // 14 <- Used for pipeline blocks
        SemanticTokenTypes.Keyword, // 15
        SemanticTokenTypes.Modifier, // 16
        SemanticTokenTypes.Comment, // 17
        SemanticTokenTypes.String, // 18
        SemanticTokenTypes.Number, // 19
        SemanticTokenTypes.Regexp, // 20
        SemanticTokenTypes.Operator, // 21
        SemanticTokenTypes.Decorator, // 22 <- Used for wrapper blocks
    )

    /**
     * Complete list of Semantic Token Modifiers supported by this server.
     */
    val LEGEND_TOKEN_MODIFIERS = listOf(
        SemanticTokenModifiers.Declaration, // 0
        SemanticTokenModifiers.Definition, // 1
        SemanticTokenModifiers.Readonly, // 2
        SemanticTokenModifiers.Static, // 3
        SemanticTokenModifiers.Deprecated, // 4
        SemanticTokenModifiers.Abstract, // 5
        SemanticTokenModifiers.Async, // 6
        SemanticTokenModifiers.Modification, // 7
        SemanticTokenModifiers.Documentation, // 8
        SemanticTokenModifiers.DefaultLibrary, // 9
        "unnecessary", // 10 - For unused imports/code dimming (IntelliJ-like)
    )

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
     * @param varsNames Names of global variables from vars/ directory (for highlighting)
     * @return List of semantic tokens for Jenkins blocks
     */
    fun getSemanticTokens(
        astModel: GroovyAstModel,
        uri: URI,
        isJenkinsFile: Boolean,
        varsNames: Set<String> = emptySet(),
    ): List<SemanticToken> {
        if (!isJenkinsFile) return emptyList()
        val tokens = mutableListOf<SemanticToken>()
        try {
            collect(SemanticTokenInput(astModel, uri), varsNames, tokens) {}
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate Jenkins semantic tokens for $uri" }
        }
        return tokens
    }

    internal fun tokensForSnapshot(
        input: SemanticTokenInput,
        isJenkinsFile: Boolean,
        varsNames: Set<String>,
        checkCancelled: () -> Unit,
    ): List<SemanticToken> {
        if (!isJenkinsFile) return emptyList()
        val tokens = mutableListOf<SemanticToken>()
        collect(input, varsNames, tokens, checkCancelled)
        return tokens
    }

    private fun collect(
        input: SemanticTokenInput,
        varsNames: Set<String>,
        tokens: MutableList<SemanticToken>,
        checkCancelled: () -> Unit,
    ) {
        checkCancelled()
        val allNodes = input.astModel.getNodes(input.uri)
        allNodes.filterIsInstance<MethodCallExpression>().forEach { methodCall ->
            checkCancelled()
            if (input.astModel.getUri(methodCall) != input.uri) return@forEach
            val methodName = methodCall.methodAsString ?: return@forEach

            // First check if it's a built-in Jenkins block
            val builtInTokenType = getTokenTypeForMethod(methodName)
            if (builtInTokenType != null) {
                val methodNode = methodCall.method
                token(input, methodNode, methodName, builtInTokenType)?.let { tokens.add(it) }
                return@forEach
            }

            // Then check if it's a vars/ global variable call
            if (methodName in varsNames) {
                val methodNode = methodCall.method
                token(input, methodNode, methodName, TokenTypes.FUNCTION)?.let { tokens.add(it) }
            }
        }
        logger.debug { "Generated ${tokens.size} Jenkins semantic tokens for ${input.uri}" }
    }

    private fun token(input: SemanticTokenInput, node: ASTNode, name: String, type: Int): SemanticToken? {
        if (node.lineNumber <= 0) return null
        val source = input.sourceRanges
        return if (source == null) {
            SemanticToken(node.lineNumber - 1, node.columnNumber - 1, name.length, type)
        } else {
            source.name(node, name)?.takeIf { it.start.line == it.end.line }?.let {
                SemanticToken(it.start.line, it.start.character, it.end.character - it.start.character, type)
            }
        }
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
