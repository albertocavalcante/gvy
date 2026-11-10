package com.github.albertocavalcante.gvy.semantics.native

import org.apache.groovy.parser.antlr4.GroovyParser
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Parameter

/**
 * A type as source writes it, without its package: `String`, `String[]`, `int[][]`, `Outer.Inner`.
 *
 * An array's own [ClassNode.getNameWithoutPackage] is the tail of a JVM descriptor (`String;` for
 * `[Ljava.lang.String;`, `[I` for `int[]`), and a nested class's is its binary name (`Outer$Inner`),
 * so every user-facing type display goes through this instead.
 */
fun ClassNode.displayName(): String =
    if (isArray) componentType.displayName() + "[]" else nameWithoutPackage.replace('$', '.')

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
