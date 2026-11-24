// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import com.github.albertocavalcante.groovyparser.api.internal.ast.MAX_HIERARCHY_DEPTH
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.lang.reflect.Modifier
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DispatchNamesHierarchyTest {
    private fun hook(owner: ClassNode) = MethodNode(
        "methodMissing",
        Modifier.PUBLIC,
        ClassHelper.OBJECT_TYPE,
        arrayOf(Parameter(ClassHelper.STRING_TYPE, "name"), Parameter(ClassHelper.OBJECT_TYPE, "args")),
        ClassNode.EMPTY_ARRAY,
        EmptyStatement(),
    ).also {
        it.lineNumber = 1
        owner.addMethod(it)
    }

    /** A leaf with [depth] superclasses below Object; the deepest implements an interface with the given hook. */
    private fun leafWithDeepInterface(depth: Int, withHook: Boolean): ClassNode {
        val iface =
            ClassNode("Hook", Modifier.PUBLIC or Modifier.INTERFACE or Modifier.ABSTRACT, ClassHelper.OBJECT_TYPE)
        if (withHook) hook(iface)
        val deepest = ClassNode("Deep", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE).apply { interfaces = arrayOf(iface) }
        var top = deepest
        repeat(depth - 1) { top = ClassNode("S$it", Modifier.PUBLIC, top) }
        return ClassNode("Leaf", Modifier.PUBLIC, top)
    }

    @Test
    fun `an interface hook on a class deeper than the hierarchy depth bound is found`() =
        assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS)) {
            val leaf = leafWithDeepInterface(MAX_HIERARCHY_DEPTH + 44, withHook = true)
            assertTrue(DispatchNames.dispatchesDynamically(listOf(leaf)))
        }

    @Test
    fun `the same deep chain without a hook does not dispatch dynamically`() =
        assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS)) {
            val leaf = leafWithDeepInterface(MAX_HIERARCHY_DEPTH + 44, withHook = false)
            assertFalse(DispatchNames.dispatchesDynamically(listOf(leaf)))
        }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
