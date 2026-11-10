package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ClassNode
import java.util.Collections
import java.util.IdentityHashMap

/*
 * Cycle-safe hierarchy queries (issue #1369). Groovy's isDerivedFrom, implementsInterface and allInterfaces follow
 * superclass and interface links without a visited set, so a user-written cycle makes them loop or overflow the
 * stack. This module cannot depend on :parser:native, which holds the same helpers for the other modules, so the
 * three it needs are kept here.
 */

private const val MAX_DEPTH = 256

private fun ClassNode.superClassesSafe(): List<ClassNode> {
    val seen = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>()).also { it.add(this) }
    val chain = ArrayList<ClassNode>()
    var current = superClass
    while (current != null && chain.size < MAX_DEPTH && seen.add(current)) {
        chain += current
        current = current.superClass
    }
    return chain
}

/** Every interface of [this], its superclasses and its super-interfaces, each once. */
internal fun ClassNode.allInterfacesSafe(): Set<ClassNode> {
    val seen = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
    val found = LinkedHashSet<ClassNode>()
    val pending = ArrayDeque(listOf(this) + superClassesSafe())
    while (pending.isNotEmpty() && seen.size < MAX_DEPTH * MAX_DEPTH) {
        val node = pending.removeFirst()
        if (!seen.add(node)) continue
        for (iface in node.interfaces.orEmpty()) {
            found += iface
            pending += iface
        }
    }
    return found
}

internal fun ClassNode.implementsInterfaceSafe(other: ClassNode): Boolean = other in allInterfacesSafe()

internal fun ClassNode.isDerivedFromSafe(other: ClassNode): Boolean = this == other || other in superClassesSafe()
