package com.github.albertocavalcante.groovylsp.ast

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.slf4j.LoggerFactory

/**
 * Advanced type resolution system for Groovy AST.
 * Provides sophisticated type inference and resolution capabilities.
 */
class TypeResolver {

    companion object {
        private val logger = LoggerFactory.getLogger(TypeResolver::class.java)
    }

    /**
     * Resolves the type of an expression in its context.
     *
     * @param expression The expression to analyze
     * @param context The surrounding context for type resolution
     * @return The resolved type information
     */
    fun resolveExpressionType(expression: Expression, context: TypeResolutionContext): TypeInfo = when (expression) {
        is VariableExpression -> resolveVariableType(expression, context)
        is MethodCallExpression -> resolveMethodCallType(expression, context)
        is PropertyExpression -> resolvePropertyType(expression, context)
        else -> TypeInfo.UNKNOWN
    }

    /**
     * Resolves the type of a variable expression.
     */
    private fun resolveVariableType(variable: VariableExpression, context: TypeResolutionContext): TypeInfo {
        // Check local scope first
        context.variableScope?.let { scope ->
            val declaredVariable = scope.getDeclaredVariable(variable.name)
            if (declaredVariable != null) {
                return TypeInfo(
                    name = declaredVariable.type.nameWithoutPackage,
                    qualifiedName = declaredVariable.type.name,
                    classNode = declaredVariable.type,
                    confidence = TypeConfidence.HIGH,
                )
            }
        }

        // Check enclosing class properties
        context.enclosingClass?.let { classNode ->
            val property = classNode.getProperty(variable.name)
            if (property != null) {
                return TypeInfo(
                    name = property.type.nameWithoutPackage,
                    qualifiedName = property.type.name,
                    classNode = property.type,
                    confidence = TypeConfidence.HIGH,
                )
            }
        }

        logger.debug("Could not resolve type for variable: ${variable.name}")
        return TypeInfo.UNKNOWN
    }

    /**
     * Resolves the return type of a method call expression.
     */
    private fun resolveMethodCallType(methodCall: MethodCallExpression, context: TypeResolutionContext): TypeInfo {
        val receiverType = if (methodCall.objectExpression != null) {
            resolveExpressionType(methodCall.objectExpression, context)
        } else {
            // Static method call or implicit this
            context.enclosingClass?.let {
                TypeInfo(
                    name = it.nameWithoutPackage,
                    qualifiedName = it.name,
                    classNode = it,
                    confidence = TypeConfidence.HIGH,
                )
            } ?: TypeInfo.UNKNOWN
        }

        if (receiverType.classNode != null) {
            val methodName = methodCall.methodAsString
            val method = findBestMatchingMethod(receiverType.classNode, methodName)

            if (method != null) {
                return TypeInfo(
                    name = method.returnType.nameWithoutPackage,
                    qualifiedName = method.returnType.name,
                    classNode = method.returnType,
                    confidence = TypeConfidence.MEDIUM,
                )
            }
        }

        logger.debug("Could not resolve method call type: ${methodCall.methodAsString}")
        return TypeInfo.UNKNOWN
    }

    /**
     * Resolves the type of a property access expression.
     */
    private fun resolvePropertyType(property: PropertyExpression, context: TypeResolutionContext): TypeInfo {
        val objectType = resolveExpressionType(property.objectExpression, context)

        if (objectType.classNode != null) {
            val propertyName = property.propertyAsString
            val classProperty = objectType.classNode.getProperty(propertyName)

            if (classProperty != null) {
                return TypeInfo(
                    name = classProperty.type.nameWithoutPackage,
                    qualifiedName = classProperty.type.name,
                    classNode = classProperty.type,
                    confidence = TypeConfidence.HIGH,
                )
            }

            // Check for getter methods
            val getterMethod = objectType.classNode.getMethod(
                "get${propertyName.replaceFirstChar {
                    it.uppercase()
                }}",
                arrayOf(),
            )
            if (getterMethod != null) {
                return TypeInfo(
                    name = getterMethod.returnType.nameWithoutPackage,
                    qualifiedName = getterMethod.returnType.name,
                    classNode = getterMethod.returnType,
                    confidence = TypeConfidence.MEDIUM,
                )
            }
        }

        logger.debug("Could not resolve property type: ${property.propertyAsString}")
        return TypeInfo.UNKNOWN
    }

    /**
     * Finds the best matching method from available overloads.
     */
    private fun findBestMatchingMethod(classNode: ClassNode, methodName: String): MethodNode? {
        val methods = classNode.getMethods(methodName)
        if (methods.isEmpty()) return null

        // For now, return the first method - could be enhanced with argument type matching
        return methods.firstOrNull()
    }

    /**
     * Resolves the type hierarchy for a given class node.
     */
    fun resolveTypeHierarchy(classNode: ClassNode): TypeHierarchy {
        val superclasses = mutableListOf<ClassNode>()
        val interfaces = mutableListOf<ClassNode>()

        // Collect superclasses
        var current = classNode.superClass
        while (current != null && current.name != "java.lang.Object") {
            superclasses.add(current)
            current = current.superClass
        }

        // Collect interfaces
        interfaces.addAll(classNode.interfaces)

        return TypeHierarchy(
            targetClass = classNode,
            superclasses = superclasses,
            interfaces = interfaces,
        )
    }
}

/**
 * Context information for type resolution.
 */
data class TypeResolutionContext(
    val enclosingClass: ClassNode? = null,
    val enclosingMethod: MethodNode? = null,
    val variableScope: VariableScope? = null,
    val imports: List<String> = emptyList(),
)

/**
 * Represents resolved type information.
 */
data class TypeInfo(
    val name: String,
    val qualifiedName: String,
    val classNode: ClassNode? = null,
    val confidence: TypeConfidence = TypeConfidence.UNKNOWN,
) {
    companion object {
        val UNKNOWN = TypeInfo("Object", "java.lang.Object", confidence = TypeConfidence.UNKNOWN)
    }
}

/**
 * Confidence level for type resolution.
 */
enum class TypeConfidence {
    HIGH, // Explicitly declared type
    MEDIUM, // Inferred from context
    LOW, // Best guess
    UNKNOWN, // Could not determine
}

/**
 * Represents the type hierarchy of a class.
 */
data class TypeHierarchy(
    val targetClass: ClassNode,
    val superclasses: List<ClassNode>,
    val interfaces: List<ClassNode>,
) {
    /**
     * Checks if the target class is assignable from the given type.
     */
    fun isAssignableFrom(other: ClassNode): Boolean {
        if (targetClass == other) return true
        if (superclasses.contains(other)) return true
        if (interfaces.contains(other)) return true
        return false
    }

    /**
     * Gets all types in the hierarchy (class + supertypes).
     */
    fun getAllTypes(): List<ClassNode> = listOf(targetClass) + superclasses + interfaces
}
