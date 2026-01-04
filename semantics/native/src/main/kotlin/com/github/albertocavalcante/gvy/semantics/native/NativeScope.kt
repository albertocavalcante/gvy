package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode

/**
 * Scope tracking for variable resolution in native Groovy AST.
 */
class NativeScope private constructor(
    private val parent: NativeScope?,
    private val variables: MutableMap<String, SemanticType> = mutableMapOf(),
) {
    /**
     * Look up a variable by name in this scope or parent scopes.
     */
    fun lookupVariable(name: String): SemanticType? = variables[name] ?: parent?.lookupVariable(name)

    /**
     * Define a variable in this scope.
     */
    fun defineVariable(name: String, type: SemanticType) {
        variables[name] = type
    }

    /**
     * Create a child scope.
     */
    fun child(): NativeScope = NativeScope(parent = this)

    companion object {
        /**
         * Create a root scope from a ClassNode.
         * Includes class fields as variables.
         */
        fun fromClass(classNode: ClassNode): NativeScope {
            val scope = NativeScope(parent = null)

            // Add fields
            for (field in classNode.fields) {
                val type = NativeTypeContext.fromClassNode(field.type)
                scope.defineVariable(field.name, type)
            }

            return scope
        }

        /**
         * Create a scope for a method.
         * Includes parameters.
         */
        fun fromMethod(methodNode: MethodNode, parent: NativeScope): NativeScope {
            val scope = parent.child()

            // Add parameters
            for (param in methodNode.parameters) {
                val type = NativeTypeContext.fromClassNode(param.type)
                scope.defineVariable(param.name, type)
            }

            return scope
        }

        /**
         * Create an empty root scope.
         */
        fun empty(): NativeScope = NativeScope(parent = null)
    }
}
