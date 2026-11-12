// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.native

import org.apache.groovy.parser.antlr4.GroovyParser
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.InnerClassNode
import org.codehaus.groovy.ast.Parameter

/**
 * A type as source writes it, without its package: `String`, `String[]`, `int[][]`, `Outer.Inner`.
 *
 * An array's own [ClassNode.getNameWithoutPackage] is the tail of a JVM descriptor (`String;` for
 * `[Ljava.lang.String;`, `[I` for `int[]`), and a nested class's is its binary name (`Outer$Inner`),
 * so every user-facing type display goes through this instead.
 *
 * Classes that source never names keep their `$` binary form (`Outer$1`, `Script$_run_closure1`),
 * which stays valid inside arrays and signatures (#1407).
 */
fun ClassNode.displayName(): String {
    if (isArray) return componentType.displayName() + "[]"
    val node = redirect()
    val inner = node as? InnerClassNode
    val outer = inner?.outerClass ?: return binaryNameDisplay(node)
    // Everything after `Outer$`: a trait's `Foo$Trait$Helper` is one generated name under `Foo`.
    val prefix = outer.name + "$"
    val own = inner.name.removePrefix(prefix).takeIf { it != inner.name } ?: inner.name.substringAfterLast('$')
    // Structural facts first: a user-declared `class _Helper` is named, whatever its spelling.
    val separator = if (inner.isAnonymous || inner.hasSyntheticMarker() || inner.isClosureClass()) "$" else "."
    return outer.displayName() + separator + own
}

// Either form of the synthetic marker: AST's own flag, or the ACC_SYNTHETIC modifier bit.
private fun ClassNode.hasSyntheticMarker(): Boolean = isSynthetic || (modifiers and ACC_SYNTHETIC) != 0

private const val ACC_SYNTHETIC = 0x1000

/**
 * Groovy's closure and lambda classes (`Script$_run_closure1`, `Script$_run_lambda1`), which Groovy
 * never marks synthetic. A user class may extend Closure too, so the declared superclass (read
 * without resolving a hierarchy) counts only together with the generated name shape.
 */
private fun InnerClassNode.isClosureClass(): Boolean =
    unresolvedSuperClass?.name == CLOSURE_CLASS && GENERATED_CLOSURE_NAME.matches(name.substringAfterLast('$'))

private const val CLOSURE_CLASS = "groovy.lang.Closure"
private val GENERATED_CLOSURE_NAME = Regex("_.*(closure|lambda)\\d+")

/**
 * The display for nodes without inner-class structure (decompiled or reflected). A synthetic node
 * (Kotlin's `Foo$WhenMappings`) keeps its whole binary name. Otherwise segments after a `$` join
 * with `.`, except that from the first segment that is empty (`Foo$$Lambda$1`) or starts with a
 * digit (anonymous) or `_` (Groovy-generated) the rest keeps its `$` form.
 */
private fun binaryNameDisplay(node: ClassNode): String {
    val name = node.nameWithoutPackage
    if (node.hasSyntheticMarker()) return name
    val segments = name.split('$')
    val generated = segments.withIndex().drop(1).firstOrNull { (_, segment) ->
        segment.firstOrNull()?.let { it.isDigit() || it == '_' } ?: true
    }?.index ?: segments.size
    val named = segments.take(generated).joinToString(".")
    return if (generated < segments.size) named + "$" + segments.drop(generated).joinToString("$") else named
}

/**
 * Whether this parameter was written `Type... name`. The AST models it as an array, exactly like
 * `Type[] name`; only the parser's context, which Groovy keeps on the parameter, records the `...`.
 * A parameter from any other source (binary classes, hand-built nodes) reads as not varargs.
 */
fun Parameter.isVarargs(): Boolean {
    val context = getNodeMetaData<Any>(PARAMETER_CONTEXT) as? GroovyParser.FormalParameterContext
    return type.isArray && context?.ELLIPSIS() != null
}

/**
 * A parameter's type as source writes it, with [render] formatting each type: `String... parts` renders
 * its element type followed by `...`; every other parameter renders its own type (`String[]` stays an array).
 * The single varargs display rule; callers with their own type renderer pass it here.
 */
fun Parameter.displayType(render: (ClassNode) -> String): String =
    if (isVarargs()) render(type.componentType) + "..." else render(type)

/** [displayType] with the shared [displayName] rule. */
fun Parameter.displayType(): String = displayType(ClassNode::displayName)

/** The node-metadata key under which Groovy's AstBuilder keeps a parameter's parse context. */
private const val PARAMETER_CONTEXT = "_PARAMETER_CONTEXT"
