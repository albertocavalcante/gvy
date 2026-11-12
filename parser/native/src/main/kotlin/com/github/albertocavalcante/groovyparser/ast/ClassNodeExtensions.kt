// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.api.internal.ast.MAX_HIERARCHY_DEPTH
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import com.github.albertocavalcante.groovyparser.api.internal.ast.findFieldSafe as sharedFindFieldSafe
import com.github.albertocavalcante.groovyparser.api.internal.ast.hasCyclicSuperClassChain as sharedHasCyclicSuperClassChain
import com.github.albertocavalcante.groovyparser.api.internal.ast.implementsInterfaceSafe as sharedImplementsInterfaceSafe
import com.github.albertocavalcante.groovyparser.api.internal.ast.isDerivedFromSafe as sharedIsDerivedFromSafe
import com.github.albertocavalcante.groovyparser.api.internal.ast.isScriptSafe as sharedIsScriptSafe
import com.github.albertocavalcante.groovyparser.api.internal.ast.methodsSafe as sharedMethodsSafe
import com.github.albertocavalcante.groovyparser.api.internal.ast.superClassChain as sharedSuperClassChain

/**
 * Extension functions for ClassNode to provide cleaner type checking.
 * These are the single source of truth for dynamic type detection across the LSP.
 */

/**
 * Check if this ClassNode represents a dynamic type.
 *
 * In Groovy, `def` variables are typed as DYNAMIC_TYPE or OBJECT_TYPE.
 * This extension consolidates the check in one place.
 */
@Suppress("DEPRECATION") // DYNAMIC_TYPE is deprecated but still needed for Groovy AST compat
fun ClassNode.isDynamic(): Boolean = ClassHelper.isDynamicTyped(this) ||
    this == ClassHelper.OBJECT_TYPE ||
    this == ClassHelper.DYNAMIC_TYPE

/**
 * Check if this ClassNode is dynamic or unresolved Object type.
 * Alias for backward compatibility.
 */
fun ClassNode.isDynamicOrObject(): Boolean = isDynamic()

/*
 * Cycle-safe class hierarchy walks (issue #1369). The implementations are shared with parser:core and dsl:gdsl in
 * parser:api's internal `groovyparser.api.internal.ast` package (#1411); these keep the existing names and imports.
 */

/** @see com.github.albertocavalcante.groovyparser.api.internal.ast.superClassChain */
fun ClassNode.superClassChain(maxDepth: Int = MAX_HIERARCHY_DEPTH): Sequence<ClassNode> =
    sharedSuperClassChain(maxDepth)

/** @see com.github.albertocavalcante.groovyparser.api.internal.ast.hasCyclicSuperClassChain */
fun ClassNode.hasCyclicSuperClassChain(maxDepth: Int = MAX_HIERARCHY_DEPTH): Boolean =
    sharedHasCyclicSuperClassChain(maxDepth)

/** @see com.github.albertocavalcante.groovyparser.api.internal.ast.isDerivedFromSafe */
fun ClassNode.isDerivedFromSafe(other: ClassNode): Boolean = sharedIsDerivedFromSafe(other)

/** @see com.github.albertocavalcante.groovyparser.api.internal.ast.isScriptSafe */
fun ClassNode.isScriptSafe(): Boolean = sharedIsScriptSafe()

/** @see com.github.albertocavalcante.groovyparser.api.internal.ast.findFieldSafe */
fun ClassNode.findFieldSafe(name: String): FieldNode? = sharedFindFieldSafe(name)

/** @see com.github.albertocavalcante.groovyparser.api.internal.ast.methodsSafe */
fun ClassNode.methodsSafe(name: String): List<MethodNode> = sharedMethodsSafe(name)

/** @see com.github.albertocavalcante.groovyparser.api.internal.ast.implementsInterfaceSafe */
fun ClassNode.implementsInterfaceSafe(other: ClassNode): Boolean = sharedImplementsInterfaceSafe(other)
