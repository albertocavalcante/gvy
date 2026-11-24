// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/*
 * INTERNAL TO GVY - NOT PUBLIC API.
 *
 * Cycle-safe class hierarchy walks, shared by parser:core, parser:native, dsl:gdsl, gls and semantics:core (#1369,
 * #1411, #2401). They live in parser:api only because it is the one module all of those already depend on.
 * Everything in the `groovyparser.api.internal` packages may change or move without notice; do not use it from outside
 * this repository.
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
 * class, its superclasses or any super-interface (visited by identity, so interface cycles terminate). At most
 * [MAX_HIERARCHY_NODES] classes and interfaces are visited, and the superclass chain is cut at
 * [MAX_HIERARCHY_DEPTH]; beyond that an interface is reported as not implemented. A caller that must tell "no"
 * from "not known" uses [implementsInterfaceChecked].
 */
fun ClassNode.implementsInterfaceSafe(other: ClassNode): Boolean =
    implementsInterfaceChecked(other) == HierarchyMembership.MEMBER

/**
 * Like [implementsInterfaceSafe], but a bound that stopped the walk before it saw every reachable interface is
 * reported as [HierarchyMembership.UNKNOWN], not as a definite "no".
 */
fun ClassNode.implementsInterfaceChecked(other: ClassNode): HierarchyMembership {
    var found = false
    val exhausted = walkInterfaces { iface ->
        found = iface == other
        !found
    }
    return when {
        found -> HierarchyMembership.MEMBER
        exhausted -> HierarchyMembership.NOT_MEMBER
        else -> HierarchyMembership.UNKNOWN
    }
}

/**
 * Like [isDerivedFromSafe], but a superclass chain cut at [MAX_HIERARCHY_DEPTH] before [other] was seen is
 * [HierarchyMembership.UNKNOWN]. A chain that ends at the root or loops back on itself was fully explored.
 */
fun ClassNode.isDerivedFromChecked(other: ClassNode): HierarchyMembership = when {
    isDerivedFromSafe(other) -> HierarchyMembership.MEMBER
    superClassChainCutByDepth(superClassChain().toList()) -> HierarchyMembership.UNKNOWN
    else -> HierarchyMembership.NOT_MEMBER
}

/** Whether this class extends or implements [other], as [isDerivedFromChecked] and [implementsInterfaceChecked]. */
fun ClassNode.isSubtypeChecked(other: ClassNode): HierarchyMembership =
    isDerivedFromChecked(other).or { implementsInterfaceChecked(other) }

/**
 * Every interface of this class and their super-interfaces, each once, in discovery order, as an [InterfaceWalk]:
 * [InterfaceWalk.Complete] when the walk saw everything reachable, [InterfaceWalk.Truncated] when a bound stopped
 * it. Superclasses are not followed (a caller that wants them walks [superClassChain] and calls this on each, so
 * the depth bound of that chain does not make every one of its members unknown). Once [MAX_HIERARCHY_NODES]
 * classes and interfaces have been visited, no further one is expanded. The interfaces of the last expanded class
 * are still added, so the set can exceed the bound by that class's fan-out.
 */
fun ClassNode.allInterfacesChecked(): InterfaceWalk {
    val found = LinkedHashSet<ClassNode>()
    val exhausted = walkInterfaces(includeSuperClasses = false) { iface ->
        found += iface
        true
    }
    return if (exhausted) InterfaceWalk.Complete(found) else InterfaceWalk.Truncated(found)
}

/** Whether a class is, or has, a given supertype: a bound that stops a walk is [UNKNOWN], not [NOT_MEMBER]. */
enum class HierarchyMembership {
    MEMBER,
    NOT_MEMBER,
    UNKNOWN,
    ;

    /** This answer when it is decisive, else the answer of [next], and [UNKNOWN] if that is not [MEMBER] either. */
    inline fun or(next: () -> HierarchyMembership): HierarchyMembership = when (this) {
        MEMBER -> MEMBER
        NOT_MEMBER -> next()
        UNKNOWN -> if (next() == MEMBER) MEMBER else UNKNOWN
    }
}

/** The interfaces a bounded walk found, and whether it saw all of them. */
sealed interface InterfaceWalk {
    val interfaces: Set<ClassNode>

    /** The walk visited every reachable interface. */
    data class Complete(override val interfaces: Set<ClassNode>) : InterfaceWalk

    /** A bound stopped the walk: more interfaces than [interfaces] may exist. */
    data class Truncated(override val interfaces: Set<ClassNode>) : InterfaceWalk
}

/** True when a depth-bounded [chain] of this class stopped before the root and before any repeated node. */
private fun ClassNode.superClassChainCutByDepth(chain: List<ClassNode>): Boolean {
    if (chain.size < MAX_HIERARCHY_DEPTH) return false
    val next = chain.last().superClass ?: return false
    return next !== this && chain.none { it === next }
}

/**
 * Breadth-first walk over the interfaces reachable from this class; [visit] returns false to stop early. True when
 * every reachable class and interface was visited, false when [visit] stopped it or a bound did.
 */
private inline fun ClassNode.walkInterfaces(
    includeSuperClasses: Boolean = true,
    visit: (ClassNode) -> Boolean,
): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
    val pending = ArrayDeque<ClassNode>()
    pending.add(this)
    val chain = if (includeSuperClasses) superClassChain().toList() else emptyList()
    pending.addAll(chain)
    var visited = 0
    while (pending.isNotEmpty() && visited < MAX_HIERARCHY_NODES) {
        val node = pending.removeFirst()
        if (!seen.add(node)) continue
        visited++
        for (iface in node.interfaces.orEmpty()) {
            if (!visit(iface)) return false
            pending.add(iface)
        }
    }
    return pending.all { it in seen } && !superClassChainCutByDepth(chain)
}

/** Default depth bound for [superClassChain] and [hasCyclicSuperClassChain]. */
const val MAX_HIERARCHY_DEPTH = 256

/** Bound on the classes and interfaces visited by an interface walk; pinned by ClassHierarchyBoundsTest. */
const val MAX_HIERARCHY_NODES = 4096
