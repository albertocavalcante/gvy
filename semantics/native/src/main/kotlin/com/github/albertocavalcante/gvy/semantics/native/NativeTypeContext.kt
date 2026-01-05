package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculatorRegistry
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode

/**
 * TypeContext implementation for native Groovy AST.
 * Bridges between TypeSolver (from parser/core) and SemanticType.
 */
class NativeTypeContext(
    private val typeSolver: TypeSolver,
    private val calculatorRegistry: TypeCalculatorRegistry,
    private val scope: NativeScope,
    override val isStaticCompilation: Boolean = false,
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
            // Strategy 1: Native AST (same module)
            val method = findMethodInModule(receiverType.fqn, methodName, argumentTypes)
            if (method != null) {
                return fromClassNode(method.returnType)
            }

            // Strategy 2: TypeSolver (classpath) - future enhancement
            // For now, return Unknown if not found in same module
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
    ): org.codehaus.groovy.ast.MethodNode? {
        // Extract class name from FQN
        val className = classFqn.substringAfterLast('.')

        return scope.currentModule?.classes
            ?.find { it.name == className }
            ?.methods
            ?.find { method ->
                method.name == methodName &&
                    method.parameters.size == argumentTypes.size
            }
    }

    override fun getFieldType(receiverType: SemanticType, fieldName: String): SemanticType? = when (receiverType) {
        is SemanticType.Known -> {
            // Strategy 1: Native AST (same module)
            val field = findFieldInModule(receiverType.fqn, fieldName)
            if (field != null) {
                return fromClassNode(field.type)
            }

            // Strategy 2: TypeSolver (classpath) - future enhancement
            // For now, return Unknown if not found in same module
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
    private fun findFieldInModule(classFqn: String, fieldName: String): org.codehaus.groovy.ast.FieldNode? {
        // Extract class name from FQN
        val className = classFqn.substringAfterLast('.')

        return scope.currentModule?.classes
            ?.find { it.name == className }
            ?.fields
            ?.find { it.name == fieldName }
    }

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
