package com.github.albertocavalcante.groovylsp.providers.semantictokens

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import org.slf4j.LoggerFactory
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
 * This provider visits the AST and resolves each identifier to its
 * declaration type, mapping it to the appropriate LSP semantic token type.
 */
@Suppress("TooManyFunctions")
object GroovySemanticTokenProvider {

    private val logger = LoggerFactory.getLogger(GroovySemanticTokenProvider::class.java)

    /**
     * Type alias for semantic tokens.
     * Uses the shared SemanticToken type from JenkinsSemanticTokenProvider.
     */
    typealias SemanticToken = JenkinsSemanticTokenProvider.SemanticToken

    /**
     * Token type indices matching LSP semantic token legend.
     * Derived from JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES to ensure consistency.
     */
    object TokenTypes {
        // Derive indices from the shared legend to prevent misalignment
        private val LEGEND = JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES

        val NAMESPACE = LEGEND.indexOf(SemanticTokenTypes.Namespace)
        val TYPE = LEGEND.indexOf(SemanticTokenTypes.Type)
        val CLASS = LEGEND.indexOf(SemanticTokenTypes.Class)
        val ENUM = LEGEND.indexOf(SemanticTokenTypes.Enum)
        val INTERFACE = LEGEND.indexOf(SemanticTokenTypes.Interface)
        val STRUCT = LEGEND.indexOf(SemanticTokenTypes.Struct)
        val TYPE_PARAMETER = LEGEND.indexOf(SemanticTokenTypes.TypeParameter)
        val PARAMETER = LEGEND.indexOf(SemanticTokenTypes.Parameter)
        val VARIABLE = LEGEND.indexOf(SemanticTokenTypes.Variable)
        val PROPERTY = LEGEND.indexOf(SemanticTokenTypes.Property)
        val ENUM_MEMBER = LEGEND.indexOf(SemanticTokenTypes.EnumMember)
        val EVENT = LEGEND.indexOf(SemanticTokenTypes.Event)
        val FUNCTION = LEGEND.indexOf(SemanticTokenTypes.Function)
        val METHOD = LEGEND.indexOf(SemanticTokenTypes.Method)
        val MACRO = LEGEND.indexOf(SemanticTokenTypes.Macro)
        val KEYWORD = LEGEND.indexOf(SemanticTokenTypes.Keyword)
        val MODIFIER = LEGEND.indexOf(SemanticTokenTypes.Modifier)
        val COMMENT = LEGEND.indexOf(SemanticTokenTypes.Comment)
        val STRING = LEGEND.indexOf(SemanticTokenTypes.String)
        val NUMBER = LEGEND.indexOf(SemanticTokenTypes.Number)
        val REGEXP = LEGEND.indexOf(SemanticTokenTypes.Regexp)
        val OPERATOR = LEGEND.indexOf(SemanticTokenTypes.Operator)
        val DECORATOR = LEGEND.indexOf(SemanticTokenTypes.Decorator)
    }

    /**
     * Token modifier bit masks.
     * Derived from JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS to ensure consistency.
     */
    object TokenModifiers {
        // Derive bit masks from the shared legend to prevent misalignment
        private val LEGEND = JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS

        val DECLARATION = 1 shl LEGEND.indexOf(SemanticTokenModifiers.Declaration)
        val DEFINITION = 1 shl LEGEND.indexOf(SemanticTokenModifiers.Definition)
        val READONLY = 1 shl LEGEND.indexOf(SemanticTokenModifiers.Readonly)
        val STATIC = 1 shl LEGEND.indexOf(SemanticTokenModifiers.Static)
        val DEPRECATED = 1 shl LEGEND.indexOf(SemanticTokenModifiers.Deprecated)
        val ABSTRACT = 1 shl LEGEND.indexOf(SemanticTokenModifiers.Abstract)
        val ASYNC = 1 shl LEGEND.indexOf(SemanticTokenModifiers.Async)
        val MODIFICATION = 1 shl LEGEND.indexOf(SemanticTokenModifiers.Modification)
        val DOCUMENTATION = 1 shl LEGEND.indexOf(SemanticTokenModifiers.Documentation)
        val DEFAULT_LIBRARY = 1 shl LEGEND.indexOf(SemanticTokenModifiers.DefaultLibrary)
    }

    /**
     * Generate semantic tokens for all Groovy constructs.
     *
     * @param astModel Parsed AST model
     * @param uri Document URI
     * @return List of semantic tokens
     */
    fun getSemanticTokens(astModel: GroovyAstModel, uri: URI): List<SemanticToken> {
        val tokens = mutableListOf<SemanticToken>()

        try {
            val allNodes = astModel.getAllNodes()
            val classNodes = astModel.getAllClassNodes()

            // Visit all class nodes to get declarations
            classNodes.forEach { classNode ->
                visitClassDeclaration(classNode, tokens)
            }

            // Visit all nodes to find references and other constructs
            allNodes.forEach { node ->
                when (node) {
                    is VariableExpression -> visitVariableExpression(node, tokens)
                    is PropertyExpression -> visitPropertyExpression(node, tokens)
                    is ClosureExpression -> visitClosureExpression(node, tokens)
                    // Other node types handled by class visitor
                }
            }

            logger.debug("Generated {} Groovy semantic tokens for {}", tokens.size, uri)
        } catch (e: NullPointerException) {
            logger.error("Null pointer encountered while generating semantic tokens for {}: {}", uri, e.message, e)
        } catch (e: IndexOutOfBoundsException) {
            logger.error("Index out of bounds while generating semantic tokens for {}: {}", uri, e.message, e)
        } catch (e: IllegalStateException) {
            logger.error("Illegal state while generating semantic tokens for {}: {}", uri, e.message, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // Catch remaining runtime exceptions to prevent LSP crashes
            logger.error(
                "Unexpected error generating semantic tokens for {}: {} - {}",
                uri,
                e.javaClass.simpleName,
                e.message,
                e,
            )
        }

        return tokens
    }

    /**
     * Visit a class declaration and add tokens for class name, members, etc.
     */
    private fun visitClassDeclaration(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        // Skip synthetic/generated classes
        if (classNode.isSynthetic || classNode.lineNumber < 0) {
            return
        }

        // Add token for class/interface/enum declaration
        addClassDeclarationToken(classNode, tokens)

        // Visit members
        visitClassMembers(classNode, tokens)

        // Visit superclass and interfaces
        visitClassHierarchy(classNode, tokens)
    }

    /**
     * Add token for class/interface/enum declaration.
     */
    private fun addClassDeclarationToken(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        val tokenType = when {
            classNode.isInterface -> TokenTypes.INTERFACE
            classNode.isEnum -> TokenTypes.ENUM
            else -> TokenTypes.CLASS
        }

        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (classNode.isAbstract && !classNode.isInterface) {
            modifiers = modifiers or TokenModifiers.ABSTRACT
        }
        if (classNode.isStaticClass) {
            modifiers = modifiers or TokenModifiers.STATIC
        }

        addTokenForNode(classNode, classNode.nameWithoutPackage.length, tokenType, modifiers, tokens)
    }

    /**
     * Visit class members (methods, fields, properties).
     */
    private fun visitClassMembers(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        classNode.methods.forEach { method ->
            visitMethodDeclaration(method, tokens)
        }

        classNode.fields.forEach { field ->
            visitFieldDeclaration(field, tokens)
        }

        classNode.properties.forEach { property ->
            visitPropertyDeclaration(property, tokens)
        }
    }

    /**
     * Visit superclass and interfaces (type references).
     */
    private fun visitClassHierarchy(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        // Visit superclass
        if (classNode.superClass != null && classNode.superClass.lineNumber > 0) {
            val superName = classNode.superClass.nameWithoutPackage
            if (superName != "Object") {
                addTokenForNode(
                    classNode.superClass,
                    superName.length,
                    TokenTypes.CLASS,
                    0,
                    tokens,
                )
            }
        }

        // Visit interfaces
        classNode.interfaces.forEach { interfaceNode ->
            if (interfaceNode.lineNumber > 0) {
                addTokenForNode(
                    interfaceNode,
                    interfaceNode.nameWithoutPackage.length,
                    TokenTypes.INTERFACE,
                    0,
                    tokens,
                )
            }
        }
    }

    /**
     * Visit a method declaration.
     */
    private fun visitMethodDeclaration(method: MethodNode, tokens: MutableList<SemanticToken>) {
        // Skip synthetic methods (generated getters/setters, etc.)
        if (method.isSynthetic || method.lineNumber < 0) {
            return
        }

        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (method.isStatic) {
            modifiers = modifiers or TokenModifiers.STATIC
        }
        if (method.isAbstract) {
            modifiers = modifiers or TokenModifiers.ABSTRACT
        }

        addTokenForNode(method, method.name.length, TokenTypes.METHOD, modifiers, tokens)

        // Visit parameters
        method.parameters.forEach { param ->
            visitParameter(param, tokens)
        }

        // Visit return type if present
        if (method.returnType != null && method.returnType.lineNumber > 0) {
            val typeName = method.returnType.nameWithoutPackage
            if (typeName != "Object" && typeName != "void") {
                addTokenForNode(
                    method.returnType,
                    typeName.length,
                    getTokenTypeForClassNode(method.returnType),
                    0,
                    tokens,
                )
            }
        }
    }

    /**
     * Visit a field declaration.
     */
    private fun visitFieldDeclaration(field: FieldNode, tokens: MutableList<SemanticToken>) {
        if (field.isSynthetic || field.lineNumber < 0) {
            return
        }

        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (field.isStatic) {
            modifiers = modifiers or TokenModifiers.STATIC
        }
        if (field.isFinal) {
            modifiers = modifiers or TokenModifiers.READONLY
        }

        // Enum constants are special
        if (field.owner?.isEnum == true && field.isStatic && field.isFinal) {
            addTokenForNode(field, field.name.length, TokenTypes.ENUM_MEMBER, modifiers, tokens)
        } else {
            addTokenForNode(field, field.name.length, TokenTypes.PROPERTY, modifiers, tokens)
        }

        // Visit field type
        if (field.type != null && field.type.lineNumber > 0) {
            val typeName = field.type.nameWithoutPackage
            if (typeName != "Object") {
                addTokenForNode(
                    field.type,
                    typeName.length,
                    getTokenTypeForClassNode(field.type),
                    0,
                    tokens,
                )
            }
        }
    }

    /**
     * Visit a property declaration.
     */
    private fun visitPropertyDeclaration(property: PropertyNode, tokens: MutableList<SemanticToken>) {
        // Properties in Groovy are often backed by fields, skip if synthetic
        if (property.isSynthetic || property.lineNumber < 0) {
            return
        }

        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (property.isStatic) {
            modifiers = modifiers or TokenModifiers.STATIC
        }

        // Check if the backing field is final
        if (property.field?.isFinal == true) {
            modifiers = modifiers or TokenModifiers.READONLY
        }

        addTokenForNode(property, property.name.length, TokenTypes.PROPERTY, modifiers, tokens)
    }

    /**
     * Visit a parameter.
     */
    private fun visitParameter(param: Parameter, tokens: MutableList<SemanticToken>) {
        if (param.lineNumber < 0) {
            return
        }

        val modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        addTokenForNode(param, param.name.length, TokenTypes.PARAMETER, modifiers, tokens)

        // Visit parameter type
        if (param.type != null && param.type.lineNumber > 0) {
            val typeName = param.type.nameWithoutPackage
            if (typeName != "Object") {
                addTokenForNode(
                    param.type,
                    typeName.length,
                    getTokenTypeForClassNode(param.type),
                    0,
                    tokens,
                )
            }
        }
    }

    /**
     * Visit a variable expression (variable reference).
     */
    private fun visitVariableExpression(varExpr: VariableExpression, tokens: MutableList<SemanticToken>) {
        if (varExpr.lineNumber < 0) {
            return
        }

        // Skip 'this' and 'super'
        if (varExpr.isThisExpression || varExpr.isSuperExpression) {
            return
        }

        // Try to determine the token type based on the variable
        // Check accessedVariable first for accurate type information
        val tokenType = when {
            varExpr.accessedVariable is Parameter -> TokenTypes.PARAMETER
            varExpr.accessedVariable is FieldNode -> TokenTypes.PROPERTY
            varExpr.name == "it" -> TokenTypes.PARAMETER // Implicit closure parameter fallback
            else -> TokenTypes.VARIABLE
        }

        addTokenForNode(varExpr, varExpr.name.length, tokenType, 0, tokens)
    }

    /**
     * Visit a property expression (e.g., obj.property).
     */
    private fun visitPropertyExpression(propExpr: PropertyExpression, tokens: MutableList<SemanticToken>) {
        if (propExpr.property.lineNumber < 0) {
            return
        }

        // The property part (after the dot)
        val propertyName = propExpr.propertyAsString ?: return

        addTokenForNode(propExpr.property, propertyName.length, TokenTypes.PROPERTY, 0, tokens)
    }

    /**
     * Visit a closure expression to handle closure parameters.
     */
    private fun visitClosureExpression(closure: ClosureExpression, tokens: MutableList<SemanticToken>) {
        // Visit closure parameters
        closure.parameters?.forEach { param ->
            visitParameter(param, tokens)
        }
    }

    /**
     * Get the appropriate token type for a ClassNode.
     */
    private fun getTokenTypeForClassNode(classNode: ClassNode): Int = when {
        classNode.isInterface -> TokenTypes.INTERFACE
        classNode.isEnum -> TokenTypes.ENUM
        else -> TokenTypes.CLASS
    }

    /**
     * Add a token for an AST node if it has valid position information.
     */
    private fun addTokenForNode(
        node: ASTNode,
        length: Int,
        tokenType: Int,
        modifiers: Int,
        tokens: MutableList<SemanticToken>,
    ) {
        if (node.lineNumber > 0 && node.columnNumber > 0) {
            tokens.add(
                SemanticToken(
                    line = node.lineNumber - 1, // Convert to 0-based
                    startChar = node.columnNumber - 1, // Convert to 0-based
                    length = length,
                    tokenType = tokenType,
                    tokenModifiers = modifiers,
                ),
            )
        }
    }
}
