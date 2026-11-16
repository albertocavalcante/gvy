// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.groovyparser.ast.methodsSafe
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import java.net.URI

/** The method and class enclosing the target node, plus the fallback script classes. */
internal class EnclosingScriptScope(
    val method: MethodNode?,
    val type: ClassNode?,
    private val fallbackScripts: () -> Sequence<ClassNode>,
) {
    /**
     * Method calls are shadowed by methods, fields and properties (a closure-valued field is callable);
     * variable references only by fields and properties, since `deploy.foo` is a property lookup.
     */
    fun declares(name: String, includeMethods: Boolean): Boolean =
        (type?.let { sequenceOf(it) } ?: fallbackScripts()).any { it.declaresMember(name, includeMethods) }

    private fun ClassNode.declaresMember(name: String, includeMethods: Boolean): Boolean =
        (includeMethods && methodsSafe(name).isNotEmpty()) ||
            getDeclaredField(name) != null ||
            getProperty(name) != null
}

/**
 * Find the method and class enclosing the target node.
 *
 * The class comes from the AST parent chain, using the declaring class of the first method,
 * field or property on the way. A script with no top-level statements gets no source position
 * for its script class, so the tracker never records that class as a parent; the declaring class
 * of the enclosing method still reaches it. When nothing on the chain names a class, shadowing
 * falls back to the script classes of the model, preferring the document's own.
 */
internal fun findEnclosingScope(model: GroovyAstModel, node: ASTNode, uri: URI): EnclosingScriptScope {
    val chain = generateSequence(model.getParent(node)) { model.getParent(it) }.toList()
    return EnclosingScriptScope(
        method = chain.firstNotNullOfOrNull { it as? MethodNode },
        type = chain.firstNotNullOfOrNull { it.declaringClassOrSelf() },
        fallbackScripts = { scriptClassesOf(model, uri) },
    )
}

private fun ASTNode.declaringClassOrSelf(): ClassNode? = when (this) {
    is ClassNode -> this
    is MethodNode -> declaringClass
    is FieldNode -> declaringClass
    is PropertyNode -> declaringClass
    else -> null
}

/**
 * Script classes for [documentUri]. An untracked script class has no recorded URI, so classes
 * with an unknown URI are kept; at worst that skips vars/ lookup and defers to later strategies.
 */
private fun scriptClassesOf(model: GroovyAstModel, documentUri: URI): Sequence<ClassNode> =
    model.getAllClassNodes().asSequence()
        .filter { it.isScriptSafe() }
        .filter { cls -> model.getUri(cls).let { it == null || it == documentUri } }
