package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import com.github.albertocavalcante.groovylsp.converters.toLspLocation
import com.github.albertocavalcante.groovyparser.ast.TypeInferencer
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Location
import org.slf4j.LoggerFactory

/**
 * Concrete implementation of TypeResolver for Groovy code.
 * Combines patterns from IntelliJ, kotlin-lsp, and fork-groovy-language-server.
 */
class GroovyTypeResolver(
    private val typeCalculator: GroovyTypeCalculator = GroovyTypeCalculator().apply {
        register(DefaultTypeCalculator())
    },
) : TypeResolver {

    private val logger = LoggerFactory.getLogger(GroovyTypeResolver::class.java)

    override suspend fun resolveType(node: ASTNode, context: CompilationContext): ClassNode? = when (node) {
        // Specific expression types FIRST (before general Expression)
        is VariableExpression -> resolveVariableType(node, context)
        is MethodCallExpression -> resolveMethodReturnType(node, context)
        is PropertyExpression -> resolvePropertyType(node, context)
        is ListExpression -> ClassHelper.make(ArrayList::class.java)
        // General Expression as fallback
        is Expression -> typeCalculator.calculateType(node, context)
        // Non-expression nodes
        is ClassNode -> node
        is FieldNode -> node.type
        is PropertyNode -> node.type
        is MethodNode -> node.returnType
        is Parameter -> node.type
        else -> {
            logger.debug("Cannot resolve type for node type: ${node.javaClass.simpleName}")
            null
        }
    }

    override suspend fun resolveTypeDefinition(node: ASTNode, context: CompilationContext): Location? {
        val typeNode = resolveType(node, context) ?: return null
        return resolveClassLocation(typeNode, context)
    }

    override suspend fun resolveClassLocation(classNode: ClassNode, context: CompilationContext): Location? = when {
        // Skip primitives and arrays - they don't have meaningful source locations
        ClassHelper.isPrimitiveType(classNode) -> null
        classNode.isArray -> resolveClassLocation(classNode.componentType, context)

        // Look for the class in the current compilation unit first
        classNode.module == context.moduleNode -> {
            val declared = context.moduleNode.classes.firstOrNull { declared ->
                declared.name == classNode.name || declared.nameWithoutPackage == classNode.nameWithoutPackage
            }
            val target = declared ?: classNode
            target.toLspLocation(context.astModel)
        }

        // Try to match declared classes in the module by fully qualified name
        else -> {
            val targetName = classNode.name
            val candidate = context.moduleNode.classes.firstOrNull { declared ->
                declared.name == targetName || declared.nameWithoutPackage == classNode.nameWithoutPackage
            }
            if (candidate != null) {
                logger.info(
                    "Resolved external class ${classNode.name} to candidate at line ${candidate.lineNumber}, URI = " +
                        "${context.astModel.getUri(candidate)}",
                )
                candidate.toLspLocation(context.astModel)
            } else {
                logger.info(
                    "No class match found for ${classNode.name}, available: ${
                        context.moduleNode.classes.map {
                            it.name
                        }
                    }",
                )
                findExternalClassLocation(classNode)
            }
        }
    }

    @Suppress("DEPRECATION") // Groovy's DYNAMIC_TYPE is deprecated in newer APIs but still required here
    private suspend fun resolveVariableType(variable: VariableExpression, context: CompilationContext): ClassNode? {
        // Follow IntelliJ pattern: Variable -> originType -> resolved type
        val resolvedType = when (val accessedVariable = variable.accessedVariable) {
            is Variable -> accessedVariable.originType ?: accessedVariable.type
            else -> variable.type
        }

        // If type is null, Object, or DYNAMIC_TYPE, try type inference from initializer
        if (resolvedType == null ||
            resolvedType == ClassHelper.OBJECT_TYPE ||
            resolvedType == ClassHelper.DYNAMIC_TYPE
        ) {
            // Look for variable declaration with initializer in the AST
            val initializerType = findVariableInitializerType(variable.name, context)
            if (initializerType != null) {
                return initializerType
            }
        }

        return resolvedType
    }

    /**
     * Find the type of a variable's initializer expression.
     * Uses TypeInferencer for expression type inference with functional approach.
     */
    private fun findVariableInitializerType(variableName: String, context: CompilationContext): ClassNode? =
        context.astModel.getAllNodes()
            .asSequence()
            .filterIsInstance<DeclarationExpression>()
            .firstOrNull { decl ->
                (decl.leftExpression as? VariableExpression)?.name == variableName
            }
            ?.let { decl ->
                // Use TypeInferencer for better type inference
                val inferredTypeName = TypeInferencer.inferExpressionType(decl.rightExpression)
                // Extract raw type before angle brackets for generic types
                // e.g., "java.util.ArrayList<java.lang.Integer>" -> "java.util.ArrayList"
                val rawTypeName = inferredTypeName.substringBefore('<')
                ClassHelper.make(rawTypeName)
            }

    private suspend fun resolveMethodReturnType(
        methodCall: MethodCallExpression,
        context: CompilationContext,
    ): ClassNode? {
        // First try to get the resolved method from the AST
        val method = findMethodDeclaration(methodCall, context)
        if (method != null) {
            return method.returnType
        }

        // Fallback to expression type
        return methodCall.type
    }

    private suspend fun resolvePropertyType(property: PropertyExpression, context: CompilationContext): ClassNode? {
        // Try to resolve the property through the owner type
        val ownerType = resolveType(property.objectExpression, context)
        if (ownerType != null) {
            val propertyName = property.propertyAsString
            val field = findFieldInClass(ownerType, propertyName)
            if (field != null) {
                return field.type
            }

            // Check for getter method
            val getter = findGetterMethod(ownerType, propertyName)
            if (getter != null) {
                return getter.returnType
            }
        }

        // Fallback to expression type
        return property.type
    }

    private suspend fun findMethodDeclaration(
        methodCall: MethodCallExpression,
        context: CompilationContext,
    ): MethodNode? {
        val methodName = methodCall.methodAsString
        val objectType = resolveType(methodCall.objectExpression, context)

        return objectType?.methods?.find { method ->
            method.name == methodName &&
                isMethodCallCompatible(method, methodCall)
        }
    }

    private fun findFieldInClass(classNode: ClassNode, fieldName: String): FieldNode? = classNode.fields.find {
        it.name == fieldName
    }
        ?: classNode.superClass?.let { findFieldInClass(it, fieldName) }

    private fun findGetterMethod(classNode: ClassNode, propertyName: String): MethodNode? {
        val getterName = "get${propertyName.replaceFirstChar { it.uppercase() }}"
        return classNode.methods.find {
            it.name == getterName && it.parameters.isEmpty()
        }
    }

    private fun isMethodCallCompatible(method: MethodNode, call: MethodCallExpression): Boolean {
        // Simple compatibility check - can be enhanced with argument type checking
        val argumentsSize = call.arguments?.let { args ->
            when (args) {
                is ArgumentListExpression -> args.expressions.size
                else -> 1
            }
        } ?: 0

        return method.parameters.size == argumentsSize
    }

    private suspend fun findExternalClassLocation(classNode: ClassNode): Location? {
        // Look for the class in dependencies (JAR files, other source files)
        // This is a simplified implementation - can be enhanced with actual dependency resolution
        logger.debug("Looking for external class: ${classNode.name}")

        // For now, return null - this would be enhanced with proper dependency scanning
        return null
    }
}
