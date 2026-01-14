package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression

internal fun getClassName(targetNode: ASTNode): String? = when (targetNode) {
    is ClassNode -> targetNode.name
    is ConstructorCallExpression -> targetNode.type.name
    is ClassExpression -> targetNode.type.name
    is ImportNode -> {
        val rawName = targetNode.type?.name ?: targetNode.className ?: return null
        val fieldName = targetNode.fieldName
        if (fieldName != null && rawName.endsWith(".$fieldName")) {
            val className = rawName.substringBeforeLast(".")
            // Validate we didn't strip too much (ensure className is not empty)
            if (className.isNotEmpty()) className else rawName
        } else {
            rawName
        }
    }
    else -> null
}
