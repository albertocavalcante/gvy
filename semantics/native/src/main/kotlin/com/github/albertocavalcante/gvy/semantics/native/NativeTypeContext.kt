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
    ): SemanticType? {
        // Use TypeSolver to find method and return type
        // This is the integration point with parser/core's resolution
        // TODO: Implement using GroovySymbolResolver
        return null
    }

    override fun getFieldType(receiverType: SemanticType, fieldName: String): SemanticType? {
        // TODO: Implement field lookup
        return null
    }

    companion object {
        /**
         * Convert a Groovy ClassNode to SemanticType.
         */
        fun fromClassNode(classNode: ClassNode): SemanticType = when {
            classNode.equals(ClassHelper.dynamicType()) -> SemanticType.Dynamic()
            classNode.isPrimaryClassNode || classNode.redirect() != null -> {
                resolvePrimitiveOrKnown(classNode.name)
            }

            classNode.isArray -> {
                SemanticType.Array(fromClassNode(classNode.componentType))
            }

            else -> SemanticType.Known(classNode.name)
        }

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
