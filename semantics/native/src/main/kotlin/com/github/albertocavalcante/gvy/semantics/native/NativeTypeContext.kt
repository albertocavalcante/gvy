// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.native

import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculatorRegistry
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.TypeResult
import com.github.albertocavalcante.gvy.semantics.toIndexClassName
import com.github.albertocavalcante.gvy.semantics.toSemanticType
import com.github.albertocavalcante.gvy.semantics.workspace.MemberLookup
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * TypeContext implementation for native Groovy AST.
 * Bridges between TypeSolver (from parser/core) and SemanticType.
 *
 * Supports cross-file type resolution through [workspaceMemberLookup] when available.
 * Resolution strategies (in order of precedence):
 * 1. Native AST (same module) - fast, direct access
 * 2. Workspace Index (cross-file) - uses workspace-wide symbol index
 * 3. TypeSolver (classpath) - falls back to external dependencies
 *
 * @property typeSolver Resolves types from classpath
 * @property calculatorRegistry Registry for calculating types from AST nodes
 * @property scope Current scope for variable lookup
 * @property isStaticCompilation Whether static compilation is enabled
 * @property workspaceMemberLookup Optional workspace-wide member lookup for cross-file resolution
 */
class NativeTypeContext(
    private val typeSolver: TypeSolver,
    private val calculatorRegistry: TypeCalculatorRegistry,
    private val scope: NativeScope,
    override val isStaticCompilation: Boolean = false,
    private val workspaceMemberLookup: MemberLookup? = null,
) : TypeContext {

    override fun resolveType(fqn: String): SemanticType = runCatching {
        val ref = typeSolver.tryToSolveType(fqn)
        if (ref.isSolved) {
            SemanticType.Known(fqn)
        } else {
            SemanticType.Unknown("Type not found: $fqn")
        }
    }.getOrElse { e ->
        SemanticType.Unknown("Error resolving $fqn: ${e.message}")
    }

    override fun calculateType(node: Any): SemanticType = calculatorRegistry.calculate(node, this)

    override fun lookupSymbol(name: String): SemanticType? = scope.lookupVariable(name)

    internal fun lookupDeclaration(variable: VariableExpression): SemanticType? = scope.lookupDeclaration(variable)

    internal fun lookupUnbound(name: String): SemanticType? = scope.lookupUnbound(name)

    override fun getMethodReturnType(
        receiverType: SemanticType,
        methodName: String,
        argumentTypes: List<SemanticType>,
    ): SemanticType? = resolveFromHierarchyNullable(
        receiverType = receiverType,
        nativeFinder = { fqn -> findMethodInModule(fqn, methodName, argumentTypes) },
        workspaceDirect = { workspace, fqn ->
            workspace.agreedMethodType(fqn, methodName, argumentTypes.size)
        },
        converter = { fromClassNode(it.returnType) },
        notFoundMessage = { "Method $methodName not found on $it" },
    )

    /**
     * Find a method in the current module by class, method name, and argument count.
     * Argument matching is simple (count-based) for now.
     * Returns null if not found.
     */
    private fun findMethodInModule(
        classFqn: String,
        methodName: String,
        argumentTypes: List<SemanticType>,
    ): MethodNode? {
        val candidates = scope.currentModule?.classes
            ?.find { it.name == classFqn }
            ?.methods
            ?.filter { it.name == methodName && it.parameters.size == argumentTypes.size }
            .orEmpty()
        // Overloads of one arity may differ in return type; take one only when they all agree.
        return candidates.firstOrNull()?.takeIf { first ->
            candidates.all { fromClassNode(it.returnType) == fromClassNode(first.returnType) }
        }
    }

    override fun getFieldType(receiverType: SemanticType, fieldName: String): SemanticType? = when (receiverType) {
        is SemanticType.Array -> {
            // Special handling: arrays have 'length' property
            if (fieldName == "length") TypeConstants.INT else null
        }

        else -> resolveFromHierarchyNullable(
            receiverType = receiverType,
            nativeFinder = { fqn -> findFieldInModule(fqn, fieldName) },
            workspaceDirect = { workspace, fqn ->
                workspace.findField(fqn, fieldName)?.type
            },
            converter = { fromClassNode(it.type) },
            notFoundMessage = { "Field $fieldName not found on $it" },
        )
    }

    /**
     * Find a field in the current module by class and field name.
     * Returns null if not found.
     */
    private fun findFieldInModule(classFqn: String, fieldName: String): FieldNode? = scope.currentModule?.classes
        ?.find { it.name == classFqn }
        ?.fields
        ?.find { it.name == fieldName }

    /**
     * Generic member resolution helper for nullable return.
     * Implements the two-tier resolution strategy:
     * 1. Native AST (same module) - fast path
     * 2. Workspace direct lookup (cross-file). Inherited members are not searched: the index holds no
     *    hierarchy, and a by-name match on a superclass could answer for the wrong overload.
     *
     * @param T The native AST node type (MethodNode, FieldNode, etc.)
     * @param receiverType The type to resolve members from
     * @param nativeFinder Function to find member in native AST by FQN
     * @param workspaceDirect Function to find member directly in workspace by FQN
     * @param converter Function to convert native AST node to SemanticType
     * @param notFoundMessage Function to generate error message when not found
     * @return SemanticType if found, null if receiver type doesn't support member lookup
     */
    @Suppress("LongParameterList") // Strategy pattern requires multiple resolution functions
    private inline fun <T> resolveFromHierarchyNullable(
        receiverType: SemanticType,
        nativeFinder: (String) -> T?,
        workspaceDirect: (MemberLookup, String) -> SemanticType?,
        converter: (T) -> SemanticType,
        notFoundMessage: (String) -> String,
    ): SemanticType? {
        return when (receiverType) {
            is SemanticType.Known -> {
                // Strategy 1: Native AST (same module) - fast path
                val nativeMember = nativeFinder(receiverType.fqn)
                if (nativeMember != null) {
                    return converter(nativeMember)
                }

                // Strategy 2: Workspace Index (cross-file)
                workspaceMemberLookup?.let { workspace ->
                    val directMember = workspaceDirect(workspace, receiverType.fqn.toIndexClassName())
                    if (directMember != null) {
                        return directMember
                    }
                }

                // Not found in any strategy
                SemanticType.Unknown(notFoundMessage(receiverType.fqn))
            }

            else -> null // Dynamic, Null, Primitive, Union types don't support member lookup
        }
    }

    /**
     * Generic member resolution helper for Either return (TypeResult).
     * Implements the two-tier resolution strategy:
     * 1. Native AST (same module) - fast path
     * 2. Workspace direct lookup (cross-file). Inherited members are not searched: the index holds no
     *    hierarchy, and a by-name match on a superclass could answer for the wrong overload.
     *
     * @param T The native AST node type (MethodNode, FieldNode, etc.)
     * @param receiverType The type to resolve members from
     * @param nativeFinder Function to find member in native AST by FQN
     * @param workspaceDirect Function to find member directly in workspace by FQN
     * @param converter Function to convert native AST node to SemanticType
     * @param errorFactory Function to create TypeInferenceError when not found
     * @return Either TypeInferenceError or SemanticType
     */
    @Suppress("LongParameterList") // Strategy pattern requires multiple resolution functions
    private inline fun <T> resolveFromHierarchyResult(
        receiverType: SemanticType,
        nativeFinder: (String) -> T?,
        workspaceDirect: (MemberLookup, String) -> SemanticType?,
        converter: (T) -> SemanticType,
        errorFactory: (SemanticType) -> TypeInferenceError,
    ): TypeResult = when (receiverType) {
        is SemanticType.Known -> {
            // Strategy 1: Native AST (same module) - fast path
            nativeFinder(receiverType.fqn)?.let { return converter(it).right() }

            // Strategy 2: Workspace Index (cross-file)
            workspaceMemberLookup?.let { workspace ->
                workspaceDirect(workspace, receiverType.fqn.toIndexClassName())?.let { return it.right() }
            }

            // Not found, return error
            errorFactory(receiverType).left()
        }
        else -> errorFactory(receiverType).left()
    }

    // Either-returning methods for explicit error handling

    override fun resolveTypeResult(fqn: String): TypeResult = runCatching {
        val ref = typeSolver.tryToSolveType(fqn)
        if (ref.isSolved) {
            SemanticType.Known(fqn).right()
        } else {
            TypeInferenceError.TypeNotResolved(fqn, "Type not found").left()
        }
    }.getOrElse { e ->
        TypeInferenceError.TypeNotResolved(fqn, "Error resolving: ${e.message}").left()
    }

    override fun calculateTypeResult(node: Any): TypeResult = calculatorRegistry.calculateResult(node, this)

    override fun lookupSymbolResult(name: String): TypeResult = scope.lookupVariable(name)?.right()
        ?: TypeInferenceError.SymbolNotFound(name).left()

    override fun getMethodReturnTypeResult(
        receiverType: SemanticType,
        methodName: String,
        argumentTypes: List<SemanticType>,
    ): TypeResult = resolveFromHierarchyResult(
        receiverType = receiverType,
        nativeFinder = { fqn -> findMethodInModule(fqn, methodName, argumentTypes) },
        workspaceDirect = { workspace, fqn ->
            workspace.agreedMethodType(fqn, methodName, argumentTypes.size)
        },
        converter = { fromClassNode(it.returnType) },
        errorFactory = { TypeInferenceError.MethodNotFound(it, methodName, argumentTypes) },
    )

    override fun getFieldTypeResult(receiverType: SemanticType, fieldName: String): TypeResult = when (receiverType) {
        is SemanticType.Array -> {
            // Special handling: arrays have 'length' property
            if (fieldName == "length") {
                TypeConstants.INT.right()
            } else {
                TypeInferenceError.FieldNotFound(receiverType, fieldName).left()
            }
        }

        else -> resolveFromHierarchyResult(
            receiverType = receiverType,
            nativeFinder = { fqn -> findFieldInModule(fqn, fieldName) },
            workspaceDirect = { workspace, fqn ->
                workspace.findField(fqn, fieldName)?.type
            },
            converter = { fromClassNode(it.type) },
            errorFactory = { TypeInferenceError.FieldNotFound(it, fieldName) },
        )
    }

    /**
     * The return type of [methodName] on [classFqn] when it is certain: every overload of that arity must declare
     * the same known type. Overloads that disagree (or lack a type) give null, so the call stays Unknown instead
     * of taking the first overload's type.
     */
    private fun MemberLookup.agreedMethodType(classFqn: String, methodName: String, arity: Int): SemanticType? {
        val types = findMethods(classFqn, methodName, arity).map { it.type }
        return if (types.isNotEmpty() && types.none { it == null }) types.distinct().singleOrNull() else null
    }

    companion object {
        /**
         * Convert a Groovy ClassNode to SemanticType.
         */
        fun fromClassNode(classNode: ClassNode): SemanticType = classNode.toSemanticType()
    }
}
