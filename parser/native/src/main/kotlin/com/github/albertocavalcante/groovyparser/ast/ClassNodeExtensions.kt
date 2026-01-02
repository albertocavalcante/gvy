package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode

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
