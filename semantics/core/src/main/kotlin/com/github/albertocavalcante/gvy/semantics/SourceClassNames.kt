// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics

import org.codehaus.groovy.ast.ClassNode

/** Compiler or reflection metadata proves nesting; other dollar signs belong to the source name. */
fun ClassNode.sourceSimpleName(): String {
    val resolved = redirect()
    resolved.outerClass?.let { return resolved.name.removePrefix("${it.name}\$") }
    // Reflected Java member classes have no Groovy outerClass, but retain their declaring class.
    if (resolved.isResolved) {
        val type = resolved.typeClass
        if (type.declaringClass != null) return type.simpleName
    }
    return resolved.nameWithoutPackage
}
