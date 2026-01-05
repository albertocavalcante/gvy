package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculatorRegistry
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.workspace.MemberLookup
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode

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

    override fun getMethodReturnType(
        receiverType: SemanticType,
        methodName: String,
        argumentTypes: List<SemanticType>,
    ): SemanticType? = when (receiverType) {
        is SemanticType.Known -> {
            // Strategy 1: Native AST (same module) - fast path
            val method = findMethodInModule(receiverType.fqn, methodName, argumentTypes)
            if (method != null) {
                return fromClassNode(method.returnType)
            }

            // Strategy 2: Workspace Index (cross-file)
            workspaceMemberLookup?.let { workspace ->
                val memberInfo = workspace.findMethod(receiverType.fqn, methodName, argumentTypes.size)
                if (memberInfo != null) {
                    return memberInfo.type ?: SemanticType.Unknown("Method return type not indexed for $methodName")
                }

                // Check inherited members if direct lookup fails
                val allMembers = workspace.getAllMembers(receiverType.fqn, includeInherited = true)
                val inheritedMethod = allMembers.find {
                    it.name == methodName && it.kind == com.github.albertocavalcante.gvy.semantics.db.SymbolKind.METHOD
                }
                if (inheritedMethod != null) {
                    return inheritedMethod.type
                        ?: SemanticType.Unknown("Inherited method return type not indexed for $methodName")
                }
            }

            // Strategy 3: TypeSolver (classpath) - future enhancement
            // For now, return Unknown if not found in same module or workspace
            SemanticType.Unknown("Method $methodName not found on ${receiverType.fqn}")
        }

        else -> null // Dynamic, Null, Primitive, Array, Union types don't have methods
    }

    /**
     * Find a method in the current module by class, method name, and argument count.
     * Argument matching is simple (count-based) for now.
     * Returns null if not found.
     */
    private fun findMethodInModule(
        classFqn: String,
        methodName: String,
        argumentTypes: List<SemanticType>,
    ): MethodNode? = scope.currentModule?.classes
        ?.find { it.name == classFqn }
        ?.methods
        ?.find { method ->
            method.name == methodName &&
                method.parameters.size == argumentTypes.size
        }

    override fun getFieldType(receiverType: SemanticType, fieldName: String): SemanticType? = when (receiverType) {
        is SemanticType.Known -> {
            // Strategy 1: Native AST (same module) - fast path
            val field = findFieldInModule(receiverType.fqn, fieldName)
            if (field != null) {
                return fromClassNode(field.type)
            }

            // Strategy 2: Workspace Index (cross-file)
            workspaceMemberLookup?.let { workspace ->
                val memberInfo = workspace.findField(receiverType.fqn, fieldName)
                if (memberInfo != null) {
                    return memberInfo.type ?: SemanticType.Unknown("Field type not indexed for $fieldName")
                }

                // Check inherited members if direct lookup fails
                val allMembers = workspace.getAllMembers(receiverType.fqn, includeInherited = true)
                val inheritedField = allMembers.find {
                    it.name == fieldName && it.kind == com.github.albertocavalcante.gvy.semantics.db.SymbolKind.FIELD
                }
                if (inheritedField != null) {
                    return inheritedField.type
                        ?: SemanticType.Unknown("Inherited field type not indexed for $fieldName")
                }
            }

            // Strategy 3: TypeSolver (classpath) - future enhancement
            // For now, return Unknown if not found in same module or workspace
            SemanticType.Unknown("Field $fieldName not found on ${receiverType.fqn}")
        }

        is SemanticType.Array -> {
            // Special handling: arrays have 'length' property
            if (fieldName == "length") TypeConstants.INT else null
        }

        else -> null // Dynamic, Null, Primitive, Union types don't have fields
    }

    /**
     * Find a field in the current module by class and field name.
     * Returns null if not found.
     */
    private fun findFieldInModule(classFqn: String, fieldName: String): FieldNode? = scope.currentModule?.classes
        ?.find { it.name == classFqn }
        ?.fields
        ?.find { it.name == fieldName }

    companion object {
        /**
         * Convert a Groovy ClassNode to SemanticType.
         */
        fun fromClassNode(classNode: ClassNode): SemanticType = when {
            classNode.equals(ClassHelper.dynamicType()) -> SemanticType.Dynamic()
            classNode.isArray -> {
                SemanticType.Array(fromClassNode(classNode.componentType))
            }

            // Check if it's a primitive by name first
            isPrimitiveName(classNode.name) -> {
                resolvePrimitiveOrKnown(classNode.name)
            }

            classNode.isPrimaryClassNode || classNode.redirect() != classNode -> {
                resolvePrimitiveOrKnown(classNode.name)
            }

            else -> SemanticType.Known(classNode.name)
        }

        /**
         * Check if a class name represents a Java/Groovy primitive type.
         */
        private fun isPrimitiveName(name: String): Boolean = name in setOf(
            "int", "long", "double", "float", "boolean", "byte", "char", "short", "void",
        )

        private fun resolvePrimitiveOrKnown(fqn: String): SemanticType = when (fqn) {
            "int" -> TypeConstants.INT
            "long" -> TypeConstants.LONG
            "double" -> TypeConstants.DOUBLE
            "float" -> TypeConstants.FLOAT
            "boolean" -> TypeConstants.BOOLEAN
            "byte" -> TypeConstants.BYTE
            "char" -> TypeConstants.CHAR
            "short" -> TypeConstants.SHORT
            "void" -> TypeConstants.VOID
            else -> SemanticType.Known(fqn)
        }
    }
}
