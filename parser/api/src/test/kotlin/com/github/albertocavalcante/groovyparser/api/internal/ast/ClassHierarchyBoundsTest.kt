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

    /*
     * The walk visits the leaf and Object first, so the interface at chain depth d is found while expanding the
     * visit number d + 1 and needs d + 1 < MAX_HIERARCHY_NODES: the last depth found is MAX_HIERARCHY_NODES - 2.
     * Past that the answer is unknown, not "no".
     */
    @Test
    fun `an interface chain is a member up to the node bound and unknown beyond`() {
        val atBound = interfaceChain(MAX_HIERARCHY_NODES - 2)
        val pastBound = interfaceChain(MAX_HIERARCHY_NODES - 1)
        assertEquals(HierarchyMembership.MEMBER, inTime { atBound.first.implementsInterfaceChecked(atBound.second) })
        assertEquals(
            HierarchyMembership.UNKNOWN,
            inTime { pastBound.first.implementsInterfaceChecked(pastBound.second) },
        )
        assertFalse(inTime { pastBound.first.implementsInterfaceSafe(pastBound.second) })
    }

    @Test
    fun `a wide two level interface graph is a member up to the node bound and unknown beyond`() {
        val atBound = twoLevelFan(MAX_HIERARCHY_NODES - 2)
        val pastBound = twoLevelFan(MAX_HIERARCHY_NODES - 1)
        assertEquals(HierarchyMembership.MEMBER, inTime { atBound.first.implementsInterfaceChecked(atBound.second) })
        assertEquals(
            HierarchyMembership.UNKNOWN,
            inTime { pastBound.first.implementsInterfaceChecked(pastBound.second) },
        )
    }

    @Test
    fun `an interface that is not there is a definite no when the walk saw everything`() {
        val (leaf, _) = interfaceChain(MAX_HIERARCHY_NODES / 2)
        assertEquals(HierarchyMembership.NOT_MEMBER, inTime { leaf.implementsInterfaceChecked(iface("Other")) })
        val cyclic = iface("I")
        cyclic.interfaces = arrayOf(cyclic)
        val cyclicLeaf = clazz("CyclicLeaf").apply { interfaces = arrayOf(cyclic) }
        assertEquals(HierarchyMembership.NOT_MEMBER, inTime { cyclicLeaf.implementsInterfaceChecked(iface("Other")) })
    }

    @Test
    fun `an interface behind a superclass chain cut by the depth bound is unknown`() {
        val target = iface("Target")
        val deepest = clazz("Deep").apply { interfaces = arrayOf(target) }
        var top = deepest
        repeat(MAX_HIERARCHY_DEPTH + 44) { top = clazz("S$it", top) }
        val leaf = clazz("Leaf", top)
        assertEquals(HierarchyMembership.UNKNOWN, inTime { leaf.implementsInterfaceChecked(target) })
    }

    @Test
    fun `a superclass beyond the depth bound is unknown and a cycle is a definite no`() {
        val base = clazz("Base")
        var top = base
        repeat(MAX_HIERARCHY_DEPTH + 44) { top = clazz("S$it", top) }
        val leaf = clazz("Leaf", top)
        assertEquals(HierarchyMembership.UNKNOWN, inTime { leaf.isDerivedFromChecked(base) })
        assertEquals(HierarchyMembership.MEMBER, inTime { leaf.isDerivedFromChecked(top) })

        val a = ClassNode("A", 1, ClassHelper.OBJECT_TYPE)
        val b = ClassNode("B", 1, a)
        a.superClass = b
        assertEquals(HierarchyMembership.MEMBER, inTime { a.isDerivedFromChecked(b) })
        assertEquals(HierarchyMembership.NOT_MEMBER, inTime { a.isDerivedFromChecked(clazz("Other")) })
        assertEquals(
            HierarchyMembership.NOT_MEMBER,
            inTime {
                superClassChainOf(MAX_HIERARCHY_DEPTH).isDerivedFromChecked(base)
            },
        )
    }

    @Test
    fun `a subtype answer is unknown when either walk is unknown and no walk found it`() {
        val (leaf, target) = interfaceChain(MAX_HIERARCHY_NODES * 2)
        assertEquals(HierarchyMembership.UNKNOWN, inTime { leaf.isSubtypeChecked(target) })
        assertEquals(HierarchyMembership.NOT_MEMBER, inTime { clazz("Plain").isSubtypeChecked(iface("Other")) })
        val (direct, first) = interfaceChain(1)
        assertEquals(HierarchyMembership.MEMBER, inTime { direct.isSubtypeChecked(first) })
    }

    @Test
    fun `all interfaces of a long chain are truncated at the node bound`() {
        val (leaf, _) = interfaceChain(MAX_HIERARCHY_NODES * 2)
        val walk = inTime { leaf.allInterfacesChecked() }
        assertTrue(walk is InterfaceWalk.Truncated, "expected Truncated but was $walk")
        assertEquals(MAX_HIERARCHY_NODES, walk.interfaces.size)
    }

    @Test
    fun `all interfaces of a short chain are complete`() {
        val (leaf, _) = interfaceChain(3)
        val walk = inTime { leaf.allInterfacesChecked() }
        assertTrue(walk is InterfaceWalk.Complete, "expected Complete but was $walk")
        assertEquals(4, walk.interfaces.size)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
