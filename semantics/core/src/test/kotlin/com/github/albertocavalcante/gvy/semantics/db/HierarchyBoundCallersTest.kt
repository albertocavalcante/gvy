// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import com.github.albertocavalcante.groovyparser.api.internal.ast.MAX_HIERARCHY_NODES
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.lang.reflect.Modifier
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A class whose interface walk hits the node bound has an unknown hierarchy, and no caller may read that as a
 * definite "no" (#2424). The bound is 4,096 nodes; real hierarchies visit 15 to 150.
 */
class HierarchyBoundCallersTest {
    private fun <T> inTime(block: () -> T): T = assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS), block)

    private fun iface(name: String, vararg supers: ClassNode) =
        ClassNode(name, Modifier.PUBLIC or Modifier.INTERFACE or Modifier.ABSTRACT, ClassHelper.OBJECT_TYPE).apply {
            interfaces = supers.toList().toTypedArray()
        }

    /** `Leaf implements I0`, `Ik extends I(k+1)`, the last one extends [last]; longer than the node bound. */
    private fun beyondTheBound(last: ClassNode? = null, name: String = "Leaf"): ClassNode {
        var tail = iface("I${MAX_HIERARCHY_NODES + 8}", *listOfNotNull(last).toTypedArray())
        for (k in MAX_HIERARCHY_NODES + 7 downTo 0) tail = iface("I$k", tail)
        return ClassNode(name, Modifier.PUBLIC, ClassHelper.OBJECT_TYPE).apply { interfaces = arrayOf(tail) }
    }

    private fun short(): ClassNode =
        ClassNode("Short", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE).apply { interfaces = arrayOf(iface("Small")) }

    @Test
    fun `a call receiver whose subclasses cannot be searched may be overridden`() {
        val declared = iface("Target")
        val unitClass = beyondTheBound(last = declared, name = "Impl").apply {
            addMethod(
                MethodNode("run", Modifier.PUBLIC, ClassHelper.VOID_TYPE, emptyArray(), emptyArray(), EmptyStatement()),
            )
        }
        val receiver = VariableExpression("target")
        assertFalse(inTime { RuntimeReceivers.cannotOverride(declared, receiver, "run", listOf(unitClass)) })
    }

    @Test
    fun `a call receiver with a searched hierarchy and no override is certain`() {
        val declared = iface("Target")
        val receiver = VariableExpression("target")
        assertTrue(inTime { RuntimeReceivers.cannotOverride(declared, receiver, "run", listOf(short())) })
    }

    @Test
    fun `an index on a receiver whose hierarchy is unknown also marks any name`() {
        val names = UnresolvedNames()
        names.index(VariableExpression("x"), ConstantExpression("k"), { beyondTheBound() }, Access.READ)
        assertTrue(UnresolvedNames.WILDCARD in names.toSet(), "names: ${names.toSet()}")
    }

    @Test
    fun `an index on a receiver with a searched hierarchy names only what it calls`() {
        val names = UnresolvedNames()
        names.index(VariableExpression("x"), ConstantExpression("k"), { short() }, Access.READ)
        assertEquals(setOf("getAt", "k"), names.toSet())
    }

    @Test
    fun `a class whose interfaces cannot all be searched may dispatch dynamically`() {
        assertTrue(inTime { DispatchNames.dispatchesDynamically(listOf(beyondTheBound())) })
        assertFalse(inTime { DispatchNames.dispatchesDynamically(listOf(short())) })
    }

    @Test
    fun `a type whose interfaces cannot all be searched may intercept properties`() {
        assertTrue(inTime { GroovyDispatch.interceptsProperties(beyondTheBound()) })
        assertFalse(inTime { GroovyDispatch.interceptsProperties(short()) })
    }

    @Test
    fun `a type whose interfaces cannot all be searched may dispatch by itself`() {
        assertTrue(inTime { GroovyDispatch.dispatchesByItself(beyondTheBound()) })
        assertFalse(inTime { GroovyDispatch.dispatchesByItself(short()) })
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
