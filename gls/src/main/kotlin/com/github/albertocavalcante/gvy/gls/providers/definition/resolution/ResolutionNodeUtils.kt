package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Extract the class name from an AST node.
 *
 * Handles various node types including:
 * - ClassNode: direct class reference
 * - ConstructorCallExpression: `new Foo()`
 * - ClassExpression: class literal
 * - ImportNode: import statements
 * - StaticMethodCallExpression: `Math.abs()` (issue #813)
 * - MethodCallExpression: `String.valueOf()` when receiver is a class
 * - PropertyExpression: `System.out` when receiver is a class
 * - VariableExpression: when it represents a class name
 */
internal fun getClassName(targetNode: ASTNode): String? = when (targetNode) {
    is ClassNode -> targetNode.name
    is ConstructorCallExpression -> targetNode.type.name
    is ClassExpression -> targetNode.type.name
    is ImportNode -> extractClassNameFromImport(targetNode)
    is StaticMethodCallExpression -> targetNode.ownerType?.name
    is MethodCallExpression -> extractClassFromReceiver(targetNode.objectExpression)
    is PropertyExpression -> extractClassFromReceiver(targetNode.objectExpression)
    is VariableExpression -> extractClassFromReceiver(targetNode)
    else -> null
}

/**
 * Extract class name from an expression that may represent a class receiver.
 *
 * Handles:
 * - ClassExpression: direct class reference (e.g., `String` in `String.valueOf()`)
 * - VariableExpression: may be a class name if it starts with uppercase
 */
private fun extractClassFromReceiver(receiver: ASTNode): String? {
    // Direct class expression (most common case)
    if (receiver is ClassExpression) {
        return receiver.type.name
    }

    // Variable expression might be a class name
    val variableExpr = receiver as? VariableExpression ?: return null
    val type = variableExpr.type
    val declaredName = variableExpr.name

    // Check if type matches the declared name (indicates class reference)
    if (type != null) {
        val simpleName = type.nameWithoutPackage
        if (declaredName == simpleName || declaredName == type.name) {
            return type.name
        }

        // Check generic types
        val genericType = type.genericsTypes?.firstOrNull()?.type
        if (genericType != null) {
            val genericSimpleName = genericType.nameWithoutPackage
            if (declaredName == genericSimpleName || declaredName == genericType.name) {
                return genericType.name
            }
        }
    }

    // For dynamically typed variables, check if name looks like a class
    return if (variableExpr.isDynamicTyped && isLikelyClassName(declaredName)) {
        declaredName
    } else {
        null
    }
}

/**
 * Heuristic: class names typically start with an uppercase letter.
 */
private fun isLikelyClassName(name: String): Boolean = name.isNotEmpty() && name[0] in 'A'..'Z'

/**
 * Extract the class name from an ImportNode.
 *
 * For regular imports, returns the fully qualified class name.
 * For static imports (e.g., `import static Math.PI`), strips the field name
 * to return just the class name (`Math`).
 *
 * @param node The import node to extract the class name from
 * @return The fully qualified class name, or null if it cannot be determined
 */
private fun extractClassNameFromImport(node: ImportNode): String? {
    val rawName = node.type?.name ?: node.className ?: return null
    val fieldName = node.fieldName

    return if (fieldName != null && rawName.endsWith(".$fieldName")) {
        val className = rawName.substringBeforeLast(".")
        // Validate we didn't strip too much (ensure className is not empty)
        if (className.isNotEmpty()) className else rawName
    } else {
        rawName
    }
}
