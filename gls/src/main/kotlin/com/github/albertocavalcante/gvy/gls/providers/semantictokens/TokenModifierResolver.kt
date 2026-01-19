package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.eclipse.lsp4j.SemanticTokenModifiers

/**
 * Resolves modifier bits for semantic tokens.
 *
 * This class is responsible for determining which modifier flags should be set
 * for a given AST node (e.g., static, readonly, abstract, deprecated, etc.).
 *
 * Responsibilities:
 * - Calculate modifier bit masks for class declarations
 * - Calculate modifier bit masks for method declarations
 * - Calculate modifier bit masks for field/property declarations
 * - Handle special modifiers (declaration, definition, unnecessary)
 */
object TokenModifierResolver {

    /**
     * Token modifier bit masks.
     * Derived from JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS to ensure consistency.
     */
    object TokenModifiers {
        // Derive bit masks from the shared legend to prevent misalignment
        private val LEGEND = JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS

        private fun maskFor(modifier: String): Int {
            val index = LEGEND.indexOf(modifier)
            require(index >= 0) {
                "Semantic token modifier '$modifier' not found in legend. " +
                    "Check JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS."
            }
            return 1 shl index
        }

        val DECLARATION = maskFor(SemanticTokenModifiers.Declaration)
        val DEFINITION = maskFor(SemanticTokenModifiers.Definition)
        val READONLY = maskFor(SemanticTokenModifiers.Readonly)
        val STATIC = maskFor(SemanticTokenModifiers.Static)
        val DEPRECATED = maskFor(SemanticTokenModifiers.Deprecated)
        val ABSTRACT = maskFor(SemanticTokenModifiers.Abstract)
        val ASYNC = maskFor(SemanticTokenModifiers.Async)
        val MODIFICATION = maskFor(SemanticTokenModifiers.Modification)
        val DOCUMENTATION = maskFor(SemanticTokenModifiers.Documentation)
        val DEFAULT_LIBRARY = maskFor(SemanticTokenModifiers.DefaultLibrary)
        val UNNECESSARY = maskFor("unnecessary") // For unused imports dimming
    }

    /**
     * Get modifiers for a class declaration.
     *
     * @param classNode The class node to resolve
     * @return Bit mask of modifiers (DECLARATION, DEFINITION, ABSTRACT, STATIC)
     */
    fun getModifiersForClassDeclaration(classNode: ClassNode): Int {
        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (classNode.isAbstract && !classNode.isInterface) {
            modifiers = modifiers or TokenModifiers.ABSTRACT
        }
        if (classNode.isStaticClass) {
            modifiers = modifiers or TokenModifiers.STATIC
        }

        return modifiers
    }

    /**
     * Get modifiers for a method declaration.
     *
     * @param method The method node to resolve
     * @return Bit mask of modifiers (DECLARATION, DEFINITION, STATIC, ABSTRACT)
     */
    fun getModifiersForMethodDeclaration(method: MethodNode): Int {
        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (method.isStatic) {
            modifiers = modifiers or TokenModifiers.STATIC
        }
        if (method.isAbstract) {
            modifiers = modifiers or TokenModifiers.ABSTRACT
        }

        return modifiers
    }

    /**
     * Get modifiers for a field declaration.
     *
     * @param field The field node to resolve
     * @return Bit mask of modifiers (DECLARATION, DEFINITION, STATIC, READONLY)
     */
    fun getModifiersForFieldDeclaration(field: FieldNode): Int {
        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (field.isStatic) {
            modifiers = modifiers or TokenModifiers.STATIC
        }
        if (field.isFinal) {
            modifiers = modifiers or TokenModifiers.READONLY
        }

        return modifiers
    }

    /**
     * Get modifiers for a property declaration.
     *
     * @param property The property node to resolve
     * @return Bit mask of modifiers (DECLARATION, DEFINITION, STATIC, READONLY)
     */
    fun getModifiersForPropertyDeclaration(property: PropertyNode): Int {
        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (property.isStatic) {
            modifiers = modifiers or TokenModifiers.STATIC
        }

        // Check if the backing field is final
        if (property.field?.isFinal == true) {
            modifiers = modifiers or TokenModifiers.READONLY
        }

        return modifiers
    }

    /**
     * Get modifiers for an import node.
     *
     * @param importNode The import node to resolve
     * @param unusedImports Set of unused imports
     * @return Bit mask of modifiers (DECLARATION, UNNECESSARY if unused)
     */
    fun getModifiersForImport(importNode: ImportNode, unusedImports: Set<ImportNode>): Int {
        var modifiers = TokenModifiers.DECLARATION
        if (importNode in unusedImports) {
            modifiers = modifiers or TokenModifiers.UNNECESSARY
        }
        return modifiers
    }

    /**
     * Get modifiers for a parameter declaration.
     *
     * @return Bit mask with DECLARATION and DEFINITION flags
     */
    fun getModifiersForParameterDeclaration(): Int = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

    /**
     * Get modifiers for a static method call.
     *
     * @return Bit mask with STATIC flag
     */
    fun getModifiersForStaticMethodCall(): Int = TokenModifiers.STATIC
}
