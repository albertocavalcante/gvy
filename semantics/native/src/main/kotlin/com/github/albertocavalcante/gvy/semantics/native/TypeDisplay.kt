package com.github.albertocavalcante.gvy.semantics.native

import org.codehaus.groovy.ast.ClassNode

/**
 * A type as source writes it, without its package: `String`, `String[]`, `int[][]`.
 *
 * An array's own [ClassNode.getNameWithoutPackage] is the tail of a JVM descriptor (`String;` for
 * `[Ljava.lang.String;`, `[I` for `int[]`), so every user-facing display goes through this instead.
 */
fun ClassNode.displayName(): String = if (isArray) componentType.displayName() + "[]" else nameWithoutPackage
