package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import java.util.Collections
import java.util.IdentityHashMap

/*
 * Cycle-safe hierarchy queries (issue #1369). Groovy's isDerivedFrom, implementsInterface, getField and
 * getMethods(name) follow superclass links without a visited set, so a user-written cycle makes them loop forever.
 * This module cannot depend on :parser:native, which holds the same helpers for the other modules, so the ones it
 * needs are kept here. Acyclic hierarchies are answered by Groovy itself so its rules (Object, arrays) still apply.
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

internal fun ClassNode.hasCyclicSuperClassChain(): Boolean {
    val chain = superClassesSafe()
    return chain.size >= MAX_DEPTH || (chain.lastOrNull() ?: this).superClass != null
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

internal fun ClassNode.isDerivedFromSafe(other: ClassNode): Boolean = if (hasCyclicSuperClassChain()) {
    this == other || other in superClassesSafe()
} else {
    isDerivedFrom(other)
}

internal fun ClassNode.findFieldSafe(name: String): FieldNode? = if (hasCyclicSuperClassChain()) {
    (listOf(this) + superClassesSafe()).firstNotNullOfOrNull { it.getDeclaredField(name) }
} else {
    getField(name)
}

internal fun ClassNode.methodsSafe(name: String): List<MethodNode> = if (hasCyclicSuperClassChain()) {
    (listOf(this) + superClassesSafe()).flatMap { it.getDeclaredMethods(name) }
} else {
    getMethods(name)
}
