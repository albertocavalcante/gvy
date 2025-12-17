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
    is ImportNode -> targetNode.type?.name ?: targetNode.className
    else -> null
}
