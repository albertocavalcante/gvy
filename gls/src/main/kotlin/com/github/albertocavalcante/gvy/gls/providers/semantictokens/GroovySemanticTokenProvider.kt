package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import java.net.URI

/**
 * Provides semantic tokens for all Groovy files (not just Jenkins).
 *
 * Implements full semantic highlighting for Groovy language constructs:
 * - Class/Interface/Enum declarations and references
 * - Method declarations and calls
 * - Variables, parameters, properties
 * - Type references
 * - Modifiers (static, final, etc.)
 *
 * This provider coordinates semantic token generation by delegating to specialized components:
 * - TokenTypeResolver: Maps AST nodes to token types
 * - TokenModifierResolver: Calculates modifier bit masks
 * - SemanticTokenVisitor: Traverses AST and generates tokens
 */
object GroovySemanticTokenProvider {

    private val logger = KotlinLogging.logger {}

    /**
     * Type alias for semantic tokens.
     * Uses the shared SemanticToken type from JenkinsSemanticTokenProvider.
     */
    typealias SemanticToken = JenkinsSemanticTokenProvider.SemanticToken

    /**
     * Token type indices matching LSP semantic token legend.
     * Delegated to TokenTypeResolver for consistency.
     */
    object TokenTypes {
        val NAMESPACE = TokenTypeResolver.TokenTypes.NAMESPACE
        val TYPE = TokenTypeResolver.TokenTypes.TYPE
        val CLASS = TokenTypeResolver.TokenTypes.CLASS
        val ENUM = TokenTypeResolver.TokenTypes.ENUM
        val INTERFACE = TokenTypeResolver.TokenTypes.INTERFACE
        val STRUCT = TokenTypeResolver.TokenTypes.STRUCT
        val TYPE_PARAMETER = TokenTypeResolver.TokenTypes.TYPE_PARAMETER
        val PARAMETER = TokenTypeResolver.TokenTypes.PARAMETER
        val VARIABLE = TokenTypeResolver.TokenTypes.VARIABLE
        val PROPERTY = TokenTypeResolver.TokenTypes.PROPERTY
        val ENUM_MEMBER = TokenTypeResolver.TokenTypes.ENUM_MEMBER
        val EVENT = TokenTypeResolver.TokenTypes.EVENT
        val FUNCTION = TokenTypeResolver.TokenTypes.FUNCTION
        val METHOD = TokenTypeResolver.TokenTypes.METHOD
        val MACRO = TokenTypeResolver.TokenTypes.MACRO
        val KEYWORD = TokenTypeResolver.TokenTypes.KEYWORD
        val MODIFIER = TokenTypeResolver.TokenTypes.MODIFIER
        val COMMENT = TokenTypeResolver.TokenTypes.COMMENT
        val STRING = TokenTypeResolver.TokenTypes.STRING
        val NUMBER = TokenTypeResolver.TokenTypes.NUMBER
        val REGEXP = TokenTypeResolver.TokenTypes.REGEXP
        val OPERATOR = TokenTypeResolver.TokenTypes.OPERATOR
        val DECORATOR = TokenTypeResolver.TokenTypes.DECORATOR
    }

    /**
     * Token modifier bit masks.
     * Delegated to TokenModifierResolver for consistency.
     */
    object TokenModifiers {
        val DECLARATION = TokenModifierResolver.TokenModifiers.DECLARATION
        val DEFINITION = TokenModifierResolver.TokenModifiers.DEFINITION
        val READONLY = TokenModifierResolver.TokenModifiers.READONLY
        val STATIC = TokenModifierResolver.TokenModifiers.STATIC
        val DEPRECATED = TokenModifierResolver.TokenModifiers.DEPRECATED
        val ABSTRACT = TokenModifierResolver.TokenModifiers.ABSTRACT
        val ASYNC = TokenModifierResolver.TokenModifiers.ASYNC
        val MODIFICATION = TokenModifierResolver.TokenModifiers.MODIFICATION
        val DOCUMENTATION = TokenModifierResolver.TokenModifiers.DOCUMENTATION
        val DEFAULT_LIBRARY = TokenModifierResolver.TokenModifiers.DEFAULT_LIBRARY
        val UNNECESSARY = TokenModifierResolver.TokenModifiers.UNNECESSARY
    }

    /**
     * Generate semantic tokens for all Groovy constructs.
     *
     * @param astModel Parsed AST model
     * @param uri Document URI
     * @param unusedImports Set of unused ImportNodes (for marking with UNNECESSARY modifier)
     * @param moduleNode Optional ModuleNode to get imports from (for generating import tokens)
     * @param sourceText Optional source text for accurate offset calculation
     * @return List of semantic tokens
     */
    @Suppress("CyclomaticComplexMethod") // Delegating to visitor, complexity is in error handling
    fun getSemanticTokens(
        astModel: GroovyAstModel,
        uri: URI,
        unusedImports: Set<ImportNode> = emptySet(),
        moduleNode: ModuleNode? = null,
        sourceText: String? = null,
    ): List<SemanticToken> {
        val tokens = mutableListOf<SemanticToken>()

        try {
            // Create visitor with source lines for accurate offset calculation
            val visitor = SemanticTokenVisitor(sourceText?.lines() ?: emptyList())

            val allNodes = astModel.getAllNodes()
            val classNodes = astModel.getAllClassNodes()

            // Visit imports to generate tokens with UNNECESSARY modifier for unused ones
            if (moduleNode != null) {
                visitor.visitImports(moduleNode, unusedImports, tokens)
            }

            // Visit all class nodes to get declarations
            classNodes.forEach { classNode ->
                visitor.visitClassDeclaration(classNode, tokens)
            }

            // Visit all nodes to find references and other constructs
            allNodes.forEach { node ->
                when (node) {
                    is VariableExpression -> visitor.visitVariableExpression(node, tokens)
                    is PropertyExpression -> visitor.visitPropertyExpression(node, tokens)
                    is ClassExpression -> visitor.visitClassExpression(node, tokens)
                    is ClosureExpression -> visitor.visitClosureExpression(node, tokens)
                    is MethodCallExpression -> visitor.visitMethodCallExpression(node, tokens)
                    is StaticMethodCallExpression -> visitor.visitStaticMethodCallExpression(node, tokens)
                }
            }

            logger.debug { "Generated ${tokens.size} Groovy semantic tokens for $uri" }
        } catch (e: NullPointerException) {
            logger.error(e) { "Null pointer encountered while generating semantic tokens for $uri: ${e.message}" }
        } catch (e: IndexOutOfBoundsException) {
            logger.error(e) { "Index out of bounds while generating semantic tokens for $uri: ${e.message}" }
        } catch (e: IllegalStateException) {
            logger.error(e) { "Illegal state while generating semantic tokens for $uri: ${e.message}" }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Catch remaining exceptions to prevent LSP crashes
            logger.error(e) {
                "Unexpected error generating semantic tokens for $uri: ${e.javaClass.simpleName} - ${e.message}"
            }
        }

        return tokens
    }
}
