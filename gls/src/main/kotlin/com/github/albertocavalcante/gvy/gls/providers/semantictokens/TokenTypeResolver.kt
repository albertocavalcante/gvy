package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Resolves AST nodes to LSP semantic token types.
 *
 * This class is responsible for determining the appropriate semantic token type
 * for a given AST node, based on its characteristics (class, interface, enum, etc.).
 *
 * Responsibilities:
 * - Map ClassNode to CLASS/INTERFACE/ENUM token type
 * - Determine token type for variable expressions based on their declaration
 * - Provide token type for fields and parameters
 */
object TokenTypeResolver {

    /**
     * Token type indices matching LSP semantic token legend.
     * Derived from JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES to ensure consistency.
     */
    object TokenTypes {
        // Derive indices from the shared legend to prevent misalignment
        private val LEGEND = JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES

        private fun indexFor(tokenType: String): Int {
            val index = LEGEND.indexOf(tokenType)
            require(index >= 0) {
                "Semantic token type '$tokenType' not found in legend. " +
                    "Check JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES."
            }
            return index
        }

        val NAMESPACE = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Namespace)
        val TYPE = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Type)
        val CLASS = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Class)
        val ENUM = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Enum)
        val INTERFACE = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Interface)
        val STRUCT = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Struct)
        val TYPE_PARAMETER = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.TypeParameter)
        val PARAMETER = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Parameter)
        val VARIABLE = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Variable)
        val PROPERTY = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Property)
        val ENUM_MEMBER = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.EnumMember)
        val EVENT = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Event)
        val FUNCTION = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Function)
        val METHOD = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Method)
        val MACRO = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Macro)
        val KEYWORD = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Keyword)
        val MODIFIER = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Modifier)
        val COMMENT = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Comment)
        val STRING = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.String)
        val NUMBER = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Number)
        val REGEXP = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Regexp)
        val OPERATOR = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Operator)
        val DECORATOR = indexFor(org.eclipse.lsp4j.SemanticTokenTypes.Decorator)
    }

    /**
     * Get the appropriate token type for a ClassNode.
     *
     * @param classNode The class node to resolve
     * @return Token type index (CLASS, INTERFACE, or ENUM)
     */
    fun getTokenTypeForClassNode(classNode: ClassNode): Int = when {
        classNode.isInterface -> TokenTypes.INTERFACE
        classNode.isEnum -> TokenTypes.ENUM
        else -> TokenTypes.CLASS
    }

    /**
     * Get the appropriate token type for a variable expression.
     *
     * @param varExpr The variable expression to resolve
     * @return Token type index (PARAMETER, PROPERTY, or VARIABLE)
     */
    fun getTokenTypeForVariableExpression(varExpr: VariableExpression): Int = when {
        varExpr.accessedVariable is Parameter -> TokenTypes.PARAMETER
        varExpr.accessedVariable is FieldNode -> TokenTypes.PROPERTY
        // Implicit closure parameter 'it' - only when unresolved (accessedVariable is null)
        varExpr.accessedVariable == null && varExpr.name == "it" -> TokenTypes.PARAMETER
        else -> TokenTypes.VARIABLE
    }

    /**
     * Get the token type for enum member fields.
     *
     * @param field The field node to check
     * @return Token type index (ENUM_MEMBER or PROPERTY)
     */
    fun getTokenTypeForField(field: FieldNode): Int = if (field.owner?.isEnum == true && field.type == field.owner) {
        TokenTypes.ENUM_MEMBER
    } else {
        TokenTypes.PROPERTY
    }
}
