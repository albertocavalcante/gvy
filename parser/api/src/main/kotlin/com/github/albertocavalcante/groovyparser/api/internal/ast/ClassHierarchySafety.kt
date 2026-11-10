/*
 * INTERNAL TO GVY - NOT PUBLIC API.
 *
 * Cycle-safe class hierarchy walks, shared by parser:core, parser:native, dsl:gdsl and gls (#1369, #1411). They live
 * in parser:api only because it is the one module all of those already depend on. Everything in the
 * `groovyparser.api.internal` packages may change or move without notice; do not use it from outside this repository.
 */

package com.github.albertocavalcante.groovyparser.api.internal.ast

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import java.util.Collections
import java.util.IdentityHashMap

/*
 * Cycle-safe class hierarchy walks (issue #1369).
 *
 * Groovy's own ClassNode.isScript(), isDerivedFrom() and friends walk the superclass chain without a visited set,
 * so `class A extends B {}; class B extends A {}` makes them spin forever. Groovy reports the cycle as a
 * "Cycle detected" error at SEMANTIC_ANALYSIS but still hands the cyclic nodes to us, so every production walk of
 * a possibly user-written hierarchy must go through these helpers instead of the Groovy methods.
 */

/**
 * The superclass chain starting at [ClassNode.getSuperClass] (this node excluded), ending at the root or just
 * before the first node seen twice. Nodes are tracked by identity, so a cycle that is only visible through
 * redirects or unresolved placeholders (distinct instances, same name) is cut by [maxDepth] instead.
 */
fun ClassNode.superClassChain(maxDepth: Int = MAX_HIERARCHY_DEPTH): Sequence<ClassNode> = sequence {
    val seen = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
    seen.add(this@superClassChain)
    var current = this@superClassChain.superClass
    var depth = 0
    while (current != null && depth < maxDepth && seen.add(current)) {
        yield(current)
        current = current.superClass
        depth++
    }
}

/** True when the superclass chain loops back on itself (or is implausibly deep). */
fun ClassNode.hasCyclicSuperClassChain(maxDepth: Int = MAX_HIERARCHY_DEPTH): Boolean {
    var count = 0
    var last: ClassNode? = null
    for (node in superClassChain(maxDepth)) {
        last = node
        count++
    }
    return count >= maxDepth || (last ?: this).superClass != null
}

/** Cycle-safe replacement for [ClassNode.isDerivedFrom]. */
fun ClassNode.isDerivedFromSafe(other: ClassNode): Boolean = if (hasCyclicSuperClassChain()) {
    this == other || superClassChain().any { it == other }
} else {
    isDerivedFrom(other)
}

/**
 * Cycle-safe replacement for [ClassNode.isScript]. Groovy keeps the explicit script flag private and only exposes it
 * through isScript(), which walks the superclass chain, so a cyclic hierarchy is answered "not a script" here.
 */
fun ClassNode.isScriptSafe(): Boolean = !hasCyclicSuperClassChain() && isScript

/**
 * Cycle-safe replacement for [ClassNode.getField], which walks the superclass chain with no visited set and never
 * returns for a missing name on a cyclic hierarchy. Acyclic chains are answered by Groovy itself.
 */
fun ClassNode.findFieldSafe(name: String): FieldNode? = if (hasCyclicSuperClassChain()) {
    (sequenceOf(this) + superClassChain()).firstNotNullOfOrNull { it.getDeclaredField(name) }
} else {
    getField(name)
}

/**
 * Cycle-safe replacement for [ClassNode.getMethods] with a name, which recurses through superclasses with no visited
 * set (an endless, growing list on a cyclic hierarchy). Acyclic chains are answered by Groovy itself.
 */
fun ClassNode.methodsSafe(name: String): List<MethodNode> = if (hasCyclicSuperClassChain()) {
    (sequenceOf(this) + superClassChain()).flatMap { it.getDeclaredMethods(name).asSequence() }.toList()
} else {
    getMethods(name)
}

/**
 * Cycle-safe replacement for [ClassNode.implementsInterface]: true when [other] is among the interfaces of this
 * class, its superclasses or any super-interface (visited by identity, so interface cycles terminate).
 */
fun ClassNode.implementsInterfaceSafe(other: ClassNode): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
    val pending = ArrayDeque<ClassNode>()
    pending.add(this)
    pending.addAll(superClassChain())
    var visited = 0
    while (pending.isNotEmpty() && visited < MAX_HIERARCHY_NODES) {
        val node = pending.removeFirst()
        if (!seen.add(node)) continue
        visited++
        for (iface in node.interfaces.orEmpty()) {
            if (iface == other) return true
            pending.add(iface)
        }
    }
    return false
}

/** Default depth bound for [superClassChain] and [hasCyclicSuperClassChain]. */
const val MAX_HIERARCHY_DEPTH = 256
private const val MAX_HIERARCHY_NODES = 4096
