// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.api.internal.ast

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shared helpers answer on a superclass cycle where Groovy's own walks never return (#1369, #1411). */
class ClassHierarchySafetyTest {
    /** `class A extends B {}; class B extends A {}`, built by hand as Groovy leaves it after "Cycle detected". */
    private val a = ClassNode("A", 1, ClassHelper.OBJECT_TYPE)
    private val b = ClassNode("B", 1, a).also { a.superClass = it }

    private fun <T> inTime(block: () -> T): T = assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS), block)

    @Test
    fun `a cyclic chain is detected and walked once`() {
        assertTrue(inTime { a.hasCyclicSuperClassChain() })
        assertTrue(inTime { a.superClassChain().toList() } == listOf(b))
    }

    @Test
    fun `queries on a cyclic chain answer instead of hanging`() {
        val other = ClassNode("Other", 1, ClassHelper.OBJECT_TYPE)

        assertFalse(inTime { a.isScriptSafe() })
        assertTrue(inTime { a.isDerivedFromSafe(b) })
        assertFalse(inTime { a.isDerivedFromSafe(other) })
        assertTrue(inTime { a.findFieldSafe("missing") } == null)
        assertTrue(inTime { a.methodsSafe("missing") }.isEmpty())
        assertFalse(inTime { a.implementsInterfaceSafe(other) })
    }

    @Test
    fun `an acyclic script class is still a script`() {
        val script = ClassNode("Probe", 1, ClassHelper.make(groovy.lang.Script::class.java)).apply { isScript = true }

        assertTrue(inTime { script.isScriptSafe() })
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
