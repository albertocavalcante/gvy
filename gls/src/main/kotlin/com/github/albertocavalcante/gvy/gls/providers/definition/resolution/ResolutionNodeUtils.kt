package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression

internal fun getClassName(targetNode: ASTNode): String? = when (targetNode) {
    is ClassNode -> targetNode.name
    is ConstructorCallExpression -> targetNode.type.name
    is ClassExpression -> targetNode.type.name
    is ImportNode -> extractClassNameFromImport(targetNode)
    else -> null
}

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
