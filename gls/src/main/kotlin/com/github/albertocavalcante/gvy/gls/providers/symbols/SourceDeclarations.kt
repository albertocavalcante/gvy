// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.InnerClassNode

/** Methods the compiler adds to every script class. */
private val SCRIPT_BOILERPLATE_METHODS = setOf("run", "main")

private const val TRAIT_HELPER_MARKER = "\$Trait\$"

/**
 * Whether the user wrote this symbol, as opposed to the parser or compiler generating it. Outline and workspace
 * symbols list only these: parameters, imports, anonymous classes, trait helpers, enum plumbing and the field
 * behind a property are all noise to a picker.
 */
internal fun Symbol.isSourceDeclaration(): Boolean = when (this) {
    is Symbol.Import, is Symbol.Variable -> false
    is Symbol.Class -> node.isWrittenInSource()
    is Symbol.Method -> node.isWrittenInSource() && owner.isWrittenOwner() && !isScriptBoilerplate()
    is Symbol.Field -> node.isWrittenInSource() && owner.isWrittenOwner() && !isPropertyBackingField()
    is Symbol.Property -> node.isWrittenInSource() && owner.isWrittenOwner()
}

/** Whether [this] is an anonymous class (`new Runnable() { ... }`, binary name `Outer$1`). */
internal fun ClassNode.isAnonymousClass(): Boolean {
    if (this is InnerClassNode && isAnonymous) return true
    val lastSegment = name.substringAfterLast('$')
    return lastSegment.isNotEmpty() && lastSegment.all(Char::isDigit)
}

private fun ASTNode.isWrittenInSource(): Boolean {
    val synthetic = (this as? AnnotatedNode)?.isSynthetic == true
    val hasSourcePosition = lineNumber >= 1
    return !synthetic && hasSourcePosition && (this as? ClassNode)?.isWrittenClass() != false
}

private fun ClassNode.isWrittenClass(): Boolean = !isAnonymousClass() && !name.contains(TRAIT_HELPER_MARKER)

// A script class takes its position from its first statement, so a script of only methods has none.
private fun ClassNode?.isWrittenOwner(): Boolean =
    this == null || isScriptSafe() || (isWrittenClass() && lineNumber >= 1)

private fun Symbol.Method.isScriptBoilerplate(): Boolean =
    owner?.isScriptSafe() == true && name in SCRIPT_BOILERPLATE_METHODS

private fun Symbol.Field.isPropertyBackingField(): Boolean = owner?.properties.orEmpty().any { it.field === node }
