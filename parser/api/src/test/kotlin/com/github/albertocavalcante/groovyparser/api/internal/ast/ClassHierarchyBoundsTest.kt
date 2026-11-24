// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.api.internal.ast

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.lang.reflect.Modifier
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the shape and the bounds of the cycle-safe hierarchy walks on programmatically built nodes (#2401). */
class ClassHierarchyBoundsTest {
    private fun <T> inTime(block: () -> T): T = assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS), block)

    private fun clazz(name: String, superClass: ClassNode = ClassHelper.OBJECT_TYPE) = ClassNode(name, 1, superClass)

    private fun iface(name: String, vararg supers: ClassNode) =
        ClassNode(name, Modifier.PUBLIC or Modifier.INTERFACE or Modifier.ABSTRACT, ClassHelper.OBJECT_TYPE).apply {
            interfaces = supers.toList().toTypedArray()
        }

    /** A class with [length] distinct superclasses above it, the last one being Object. */
    private fun superClassChainOf(length: Int): ClassNode {
        var top: ClassNode = ClassHelper.OBJECT_TYPE
        repeat(length - 1) { top = clazz("S$it", top) }
        return clazz("Leaf", top)
    }

    /** `Leaf implements I0`, `Ik extends I(k+1)`; returns the leaf and the interface at [depth]. */
    private fun interfaceChain(depth: Int): Pair<ClassNode, ClassNode> {
        var tail = iface("I$depth")
        val target = tail
        for (k in depth - 1 downTo 0) tail = iface("I$k", tail)
        return clazz("Leaf").apply { interfaces = arrayOf(tail) } to target
    }

    /** `Leaf implements P0..P(width-1)`, each `Pi extends Qi`; returns the leaf and the last Q. */
    private fun twoLevelFan(width: Int): Pair<ClassNode, ClassNode> {
        val qs = List(width) { iface("Q$it") }
        val ps = List(width) { iface("P$it", qs[it]) }
        return clazz("Leaf").apply { interfaces = ps.toTypedArray() } to qs.last()
    }

    @Test
    fun `a linear chain is acyclic and answered by Groovy`() {
        val base = clazz("Base")
        val leaf = clazz("Leaf", base)
        assertFalse(inTime { leaf.hasCyclicSuperClassChain() })
        assertTrue(inTime { leaf.isDerivedFromSafe(base) })
        assertEquals(listOf(base, ClassHelper.OBJECT_TYPE), inTime { leaf.superClassChain().toList() })
    }

    @Test
    fun `a self cycle and a two cycle are cyclic and terminate`() {
        val self = clazz("Self").also { it.superClass = it }
        val a = clazz("A")
        val b = clazz("B", a).also { a.superClass = it }
        assertTrue(inTime { self.hasCyclicSuperClassChain() })
        assertTrue(inTime { a.hasCyclicSuperClassChain() })
        assertEquals(emptyList(), inTime { self.superClassChain().toList() })
        assertEquals(listOf(b), inTime { a.superClassChain().toList() })
    }

    @Test
    fun `a superclass chain is cyclic from the depth bound`() {
        assertFalse(inTime { superClassChainOf(MAX_HIERARCHY_DEPTH - 1).hasCyclicSuperClassChain() })
        assertTrue(inTime { superClassChainOf(MAX_HIERARCHY_DEPTH).hasCyclicSuperClassChain() })
        assertTrue(inTime { superClassChainOf(MAX_HIERARCHY_DEPTH + 44).hasCyclicSuperClassChain() })
        assertEquals(
            MAX_HIERARCHY_DEPTH,
            inTime {
                superClassChainOf(MAX_HIERARCHY_DEPTH + 44).superClassChain().count()
            },
        )
    }

    @Test
    fun `an interface diamond finds each interface once`() {
        val top = iface("Top")
        val left = iface("Left", top)
        val right = iface("Right", top)
        val leaf = clazz("Leaf").apply { interfaces = arrayOf(left, right) }
        assertTrue(inTime { leaf.implementsInterfaceSafe(top) })
        assertTrue(inTime { leaf.implementsInterfaceSafe(left) })
        assertFalse(inTime { leaf.implementsInterfaceSafe(iface("Other")) })
    }

    @Test
    fun `an interface cycle terminates`() {
        val i = iface("I")
        val j = iface("J", i)
        i.interfaces = arrayOf(j)
        val leaf = clazz("Leaf").apply { interfaces = arrayOf(i) }
        assertTrue(inTime { leaf.implementsInterfaceSafe(j) })
        assertFalse(inTime { leaf.implementsInterfaceSafe(iface("Other")) })
    }

    @Test
    fun `an interface walk visits at most the node bound`() {
        val below = interfaceChain(BELOW)
        val between = interfaceChain(BETWEEN)
        val above = interfaceChain(ABOVE)
        assertTrue(inTime { below.first.implementsInterfaceSafe(below.second) })
        assertFalse(inTime { between.first.implementsInterfaceSafe(between.second) }, "depth $BETWEEN")
        assertFalse(inTime { above.first.implementsInterfaceSafe(above.second) })
    }

    @Test
    fun `a wide two level interface graph is cut at the node bound`() {
        val narrow = twoLevelFan(BELOW)
        val wide = twoLevelFan(BETWEEN)
        assertTrue(inTime { narrow.first.implementsInterfaceSafe(narrow.second) })
        assertFalse(inTime { wide.first.implementsInterfaceSafe(wide.second) })
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
        const val BELOW = 100
        const val BETWEEN = 5_000
        const val ABOVE = 70_000
    }
}
